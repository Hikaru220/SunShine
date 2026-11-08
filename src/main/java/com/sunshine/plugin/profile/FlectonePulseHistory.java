package com.sunshine.plugin.profile;

import com.sunshine.plugin.SunShinePlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Читает реальные данные FlectonePulse (вайтлист, история банов/мутов/варнов) напрямую из его
 * таблиц {@code fp_player}/{@code fp_moderation} - работает только после переноса FlectonePulse
 * с H2 на MySQL (см. {@code /sunshine migrate-flectonepulse} и раздел 10 GUIDE.md). Подключается
 * к той же MySQL, что и {@code player-db}, - FlectonePulse после переноса живёт там же (таблицы
 * с префиксом {@code fp_}).
 * <p>
 * Read-only: этот класс никогда не пишет в таблицы FlectonePulse, только читает.
 */
public final class FlectonePulseHistory {

    private final SunShinePlugin plugin;
    private Connection connection;

    public FlectonePulseHistory(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    private synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            var config = plugin.config();
            String url = "jdbc:mysql://" + config.playerDbHost() + ":" + config.playerDbPort()
                    + "/" + config.playerDbName() + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true";
            connection = DriverManager.getConnection(url, config.playerDbUser(), config.playerDbPassword());
        }
        return connection;
    }

    /**
     * Реально ли игрок в вайтлисте по данным самого FlectonePulse (таблица fp_moderation,
     * type=WHITELIST, valid=1) - НЕ ванильный Bukkit-вайтлист, если вайтлист-команда сервера
     * (applications.whitelist-command) на самом деле принадлежит FlectonePulse.
     *
     * @return true/false, или null если запрос не удался (таблиц ещё нет - FlectonePulse не переносили,
     *         или БД недоступна) - в этом случае стоит показать резервный ванильный статус.
     */
    public synchronized Boolean isWhitelisted(UUID uuid) {
        String sql = "SELECT COUNT(*) FROM fp_moderation m JOIN fp_player p ON p.id = m.player "
                + "WHERE p.uuid = ? AND m.type = 'WHITELIST' AND m.valid = 1";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("FlectonePulse-БД: не удалось проверить вайтлист для " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Суммарное время онлайна игрока (секунды) - читается напрямую из {@code fp_time.total}
     * (FlectonePulse хранит в миллисекундах). Это те же данные, что отдаёт его собственный
     * плейсхолдер, но без гонки с моментом входа/выхода игрока - надёжнее, чем зеркалирование
     * через PlaceholderAPI на quit. null, если у игрока ещё нет записи в fp_time.
     */
    public synchronized Long totalPlaytimeSeconds(UUID uuid) {
        String sql = "SELECT t.total FROM fp_time t JOIN fp_player p ON p.id = t.player WHERE p.uuid = ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long totalMs = rs.getLong(1);
                return rs.wasNull() ? null : totalMs / 1000L;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("FlectonePulse-БД: не удалось прочитать время онлайна для " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    /** Последние N наказаний игрока (бан/мут/кик/варн), от новых к старым. Пустой список = ничего не найдено или таблиц нет. */
    public List<PunishmentRecord> recentPunishments(UUID uuid, int limit) {
        return punishmentsOfTypes(uuid, List.of("BAN", "MUTE", "WARN", "KICK"), limit);
    }

    /** То же самое, но только одного типа (BAN/MUTE/WARN/KICK) - для кнопок "Список банов"/"Список мутов"/"Список варнов". */
    public List<PunishmentRecord> punishmentsOfType(UUID uuid, String type, int limit) {
        return punishmentsOfTypes(uuid, List.of(type), limit);
    }

    private synchronized List<PunishmentRecord> punishmentsOfTypes(UUID uuid, List<String> types, int limit) {
        List<PunishmentRecord> result = new ArrayList<>();
        String placeholders = String.join(", ", java.util.Collections.nCopies(types.size(), "?"));
        String sql = "SELECT m.type, m.date, m.time, m.reason, m.valid, mp.name AS moderator_name "
                + "FROM fp_moderation m "
                + "JOIN fp_player p ON p.id = m.player "
                + "JOIN fp_player mp ON mp.id = m.moderator "
                + "WHERE p.uuid = ? AND m.type IN (" + placeholders + ") "
                + "ORDER BY m.date DESC LIMIT ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, uuid.toString());
            for (String type : types) ps.setString(i++, type);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long expiry = rs.getLong("time");
                    boolean valid = rs.getBoolean("valid");
                    boolean active = valid && (expiry == -1 || expiry > System.currentTimeMillis());
                    result.add(new PunishmentRecord(
                            rs.getString("type"),
                            rs.getLong("date"),
                            expiry,
                            rs.getString("reason"),
                            rs.getString("moderator_name"),
                            active
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("FlectonePulse-БД: не удалось прочитать историю наказаний для " + uuid + ": " + e.getMessage());
        }
        return result;
    }

    /** Текущий максимальный id в fp_moderation - отправная точка для {@link ModerationLogPoller}, чтобы не спамить старую историю при запуске. */
    public synchronized long currentMaxModerationId() {
        String sql = "SELECT MAX(id) FROM fp_moderation";
        try (PreparedStatement ps = connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long max = rs.getLong(1);
                return rs.wasNull() ? 0 : max;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("FlectonePulse-БД: не удалось прочитать max(id) fp_moderation: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Новые записи fp_moderation с id больше {@code afterId}, по возрастанию - для
     * {@link ModerationLogPoller}. Ловит вообще все наказания, независимо от того, как они были
     * выданы (кнопки /profile, /sunshine punish, консоль сервера напрямую, игровые команды).
     */
    public synchronized List<ModerationLogEntry> moderationSince(long afterId, int limit) {
        List<ModerationLogEntry> result = new ArrayList<>();
        String sql = "SELECT m.id, m.type, m.date, m.time, m.reason, p.name AS player_name, mp.name AS moderator_name "
                + "FROM fp_moderation m "
                + "JOIN fp_player p ON p.id = m.player "
                + "JOIN fp_player mp ON mp.id = m.moderator "
                + "WHERE m.id > ? ORDER BY m.id ASC LIMIT ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ModerationLogEntry(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getLong("time"),
                            rs.getString("reason"),
                            rs.getString("player_name"),
                            rs.getString("moderator_name")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("FlectonePulse-БД: не удалось прочитать новые записи модерации: " + e.getMessage());
        }
        return result;
    }

    /**
     * @param id            id строки fp_moderation - для отслеживания прогресса поллинга
     * @param type          BAN/MUTE/KICK/WARN/UNBAN/... (см. Moderation.Type у FlectonePulse)
     * @param expiryEpochMs когда истекает, -1 = навсегда
     * @param reason        причина, может быть null
     * @param playerName    ник наказанного
     * @param moderatorName ник того, кто выдал ("Console", если выдано от лица консоли)
     */
    public record ModerationLogEntry(long id, String type, long expiryEpochMs, String reason,
                                      String playerName, String moderatorName) {
    }

    /**
     * @param type          BAN/MUTE/WARN/KICK
     * @param dateEpochMs    когда выдано
     * @param expiryEpochMs  когда истекает, -1 = навсегда
     * @param reason         причина, может быть null
     * @param moderatorName  ник того, кто выдал
     * @param active         valid и (навсегда или ещё не истёк)
     */
    public record PunishmentRecord(String type, long dateEpochMs, long expiryEpochMs, String reason,
                                    String moderatorName, boolean active) {
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // закрываем на выключении плагина
        }
        connection = null;
    }
}
