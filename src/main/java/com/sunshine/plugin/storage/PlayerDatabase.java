package com.sunshine.plugin.storage;

import com.sunshine.plugin.SunShinePlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;

/**
 * Зеркало привязок игроков в MySQL: то же, что лежит в {@code linked-accounts.yml}
 * (UUID, ник, Discord ID, последний IP), плюс суммарное время онлайна.
 * yml остаётся основным хранилищем — сюда данные только дублируются, для внешнего доступа.
 *
 * <p>Схема (создаётся автоматически):
 * <pre>
 *   uuid             CHAR(36) PRIMARY KEY
 *   name             VARCHAR(16)   -- ник Minecraft, последний известный
 *   discord_id       VARCHAR(32)   -- привязанный Discord, NULL если отвязан
 *   last_ip          VARCHAR(45)
 *   playtime_seconds BIGINT        -- суммарное время онлайна в секундах
 *   first_seen       TIMESTAMP
 *   updated_at       TIMESTAMP     -- обновляется при любой записи
 * </pre>
 */
public final class PlayerDatabase {

    private final SunShinePlugin plugin;
    private final String url;
    private final String user;
    private final String password;
    private final String table;

    private Connection connection;

    public PlayerDatabase(SunShinePlugin plugin, String host, int port, String database,
                          String user, String password, String table) {
        this.plugin = plugin;
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true"
                + "&connectTimeout=5000&socketTimeout=15000";
        this.user = user;
        this.password = password;
        String safe = table.replaceAll("[^A-Za-z0-9_]", "");
        this.table = safe.isBlank() ? "sunshine_players" : safe;
    }

    public synchronized void connect() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL-драйвер не загружен (проверь секцию libraries в plugin.yml)", e);
        }
        connection = DriverManager.getConnection(url, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                            + "`uuid` CHAR(36) NOT NULL,"
                            + "`name` VARCHAR(16) NULL,"
                            + "`discord_id` VARCHAR(32) NULL,"
                            + "`last_ip` VARCHAR(45) NULL,"
                            + "`playtime_seconds` BIGINT NULL,"
                            + "`first_seen` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                            + "PRIMARY KEY (`uuid`),"
                            + "KEY `idx_name` (`name`),"
                            + "KEY `idx_discord` (`discord_id`)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        plugin.getLogger().info("БД игроков подключена (" + url.substring(0, url.indexOf('?')) + "), таблица `" + table + "`.");
    }

    private synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            connect();
        }
        return connection;
    }

    /**
     * Вставляет или обновляет строку игрока. Любой из аргументов, кроме {@code uuid}, может быть
     * {@code null} — тогда это поле в БД не трогается (старое значение сохраняется).
     */
    public synchronized void upsert(UUID uuid, String name, String discordId, String lastIp, Long playtimeSeconds)
            throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO `" + table + "` (`uuid`, `name`, `discord_id`, `last_ip`, `playtime_seconds`) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "`name` = COALESCE(VALUES(`name`), `name`), "
                        + "`discord_id` = COALESCE(VALUES(`discord_id`), `discord_id`), "
                        + "`last_ip` = COALESCE(VALUES(`last_ip`), `last_ip`), "
                        + "`playtime_seconds` = COALESCE(VALUES(`playtime_seconds`), `playtime_seconds`)")) {
            ps.setString(1, uuid.toString());
            setNullable(ps, 2, name);
            setNullable(ps, 3, discordId);
            setNullable(ps, 4, lastIp);
            if (playtimeSeconds == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, playtimeSeconds);
            }
            ps.executeUpdate();
        }
    }

    /** Читает сохранённую строку игрока. Возвращает null, если такого uuid в таблице ещё нет. */
    public synchronized PlayerRow get(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT `name`, `discord_id`, `last_ip`, `playtime_seconds` FROM `" + table + "` WHERE `uuid` = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long playtime = rs.getLong("playtime_seconds");
                Long playtimeSeconds = rs.wasNull() ? null : playtime;
                return new PlayerRow(uuid, rs.getString("name"), rs.getString("discord_id"),
                        rs.getString("last_ip"), playtimeSeconds);
            }
        }
    }

    public record PlayerRow(UUID uuid, String name, String discordId, String lastIp, Long playtimeSeconds) {
    }

    /** Снимает привязку Discord, оставляя саму строку игрока (ник, время онлайна). */
    public synchronized void clearDiscord(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE `" + table + "` SET `discord_id` = NULL WHERE `uuid` = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
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
