package com.sunshine.plugin.territory;

import com.sunshine.plugin.SunShinePlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC-слой общин/территорий - живёт в той же MySQL, что и {@code player-db}/таблицы FlectonePulse
 * (см. ТЗ раздел 0: не заводим отдельную БД). Одно ленивое переиспользуемое соединение (как
 * {@code PlayerDatabase}/{@code FlectonePulseHistory}) - никакого пула соединений, чтобы не тратить
 * лишние ресурсы на маленьком сервере.
 * <p>
 * Ограничения хранятся CSV-строкой (например {@code "lock,pvp_forced"}), а не JSON - не нужна
 * отдельная JSON-библиотека ради 3 возможных значений (см. ТЗ раздел 2).
 */
public final class TerritoryDatabase {

    private final SunShinePlugin plugin;
    private Connection connection;

    public TerritoryDatabase(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void connect() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL-драйвер не загружен (проверь секцию libraries в plugin.yml)", e);
        }
        var config = plugin.config();
        String url = "jdbc:mysql://" + config.playerDbHost() + ":" + config.playerDbPort()
                + "/" + config.playerDbName() + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true"
                + "&connectTimeout=5000&socketTimeout=15000";
        connection = DriverManager.getConnection(url, config.playerDbUser(), config.playerDbPassword());
        createSchema();
        plugin.getLogger().info("Territory-БД подключена (таблицы territory_*).");
    }

    private synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            connect();
        }
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_communities ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(64) NOT NULL,"
                    + "tag VARCHAR(6) NOT NULL,"
                    + "owner_uuid CHAR(36) NOT NULL,"
                    + "world VARCHAR(64) NOT NULL,"
                    + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL,"
                    + "restrictions VARCHAR(64) NOT NULL DEFAULT '',"
                    + "closed BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "bluemap_id VARCHAR(64) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "tag_color VARCHAR(2) NOT NULL DEFAULT 'f',"
                    + "tag_bold BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "UNIQUE KEY idx_name (name),"
                    + "UNIQUE KEY idx_tag (tag),"
                    + "KEY idx_owner (owner_uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // Общины уже существовали на боевой БД до появления цвета/жирности тега - CREATE TABLE
            // IF NOT EXISTS выше их не тронет, поэтому колонки докатываем миграцией отдельно.
            ensureColumn("territory_communities", "tag_color", "VARCHAR(2) NOT NULL DEFAULT 'f'");
            ensureColumn("territory_communities", "tag_bold", "BOOLEAN NOT NULL DEFAULT FALSE");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_members ("
                    + "community_id INT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "PRIMARY KEY (community_id, uuid),"
                    + "UNIQUE KEY idx_uuid (uuid),"
                    + "CONSTRAINT fk_territory_members_community FOREIGN KEY (community_id) "
                    + "REFERENCES territory_communities(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_applications ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "type VARCHAR(10) NOT NULL,"
                    + "applicant_uuid CHAR(36) NOT NULL,"
                    + "applicant_discord_id VARCHAR(32) NOT NULL,"
                    + "target_community_id INT NULL,"
                    + "proposed_name VARCHAR(64) NULL,"
                    + "proposed_tag VARCHAR(6) NULL,"
                    + "world VARCHAR(64) NOT NULL,"
                    + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL,"
                    + "description VARCHAR(1000) NULL,"
                    + "status VARCHAR(10) NOT NULL DEFAULT 'PENDING',"
                    + "admin_channel_id VARCHAR(32) NULL,"
                    + "admin_message_id VARCHAR(32) NULL,"
                    + "decided_by VARCHAR(64) NULL,"
                    + "decided_at BIGINT NULL,"
                    + "decline_reason VARCHAR(1000) NULL,"
                    + "submitted_at BIGINT NOT NULL,"
                    + "KEY idx_applicant (applicant_uuid),"
                    + "KEY idx_target (target_community_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_meta ("
                    + "meta_key VARCHAR(64) PRIMARY KEY,"
                    + "meta_value VARCHAR(255) NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    /** Докатывает колонку в уже существующую таблицу, если её там ещё нет (миграция схемы без потери данных на боевой БД). */
    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            plugin.getLogger().info("Territory-БД: добавлена колонка " + table + "." + column + " (миграция схемы).");
        }
    }

    // ---------- Communities ----------

    /** true, если имя ИЛИ тег уже заняты другой общиной - обе проверки нужны, оба поля уникальны (ТЗ раздел 2). */
    public synchronized boolean nameOrTagTaken(String name, String tag) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COUNT(*) FROM territory_communities WHERE name = ? OR tag = ?")) {
            ps.setString(1, name);
            ps.setString(2, tag);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Создаёт общину и сразу добавляет владельца единственным участником (см. ТЗ раздел 3.2). */
    public synchronized int createCommunity(String name, String tag, UUID owner, String world,
                                             int x1, int z1, int x2, int z2) throws SQLException {
        Connection c = connection();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO territory_communities (name, tag, owner_uuid, world, x1, z1, x2, z2, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, tag);
            ps.setString(3, owner.toString());
            ps.setString(4, world);
            ps.setInt(5, x1);
            ps.setInt(6, z1);
            ps.setInt(7, x2);
            ps.setInt(8, z2);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                int id = keys.getInt(1);
                addMember(id, owner);
                return id;
            }
        }
    }

    public synchronized void updateCommunityBounds(int communityId, String world, int x1, int z1, int x2, int z2) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET world = ?, x1 = ?, z1 = ?, x2 = ?, z2 = ? WHERE id = ?")) {
            ps.setString(1, world);
            ps.setInt(2, x1);
            ps.setInt(3, z1);
            ps.setInt(4, x2);
            ps.setInt(5, z2);
            ps.setInt(6, communityId);
            ps.executeUpdate();
        }
    }

    public synchronized void setOwner(int communityId, UUID newOwner) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET owner_uuid = ? WHERE id = ?")) {
            ps.setString(1, newOwner.toString());
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    public synchronized void setRestrictions(int communityId, Set<String> restrictions) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET restrictions = ? WHERE id = ?")) {
            ps.setString(1, String.join(",", restrictions));
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    public synchronized void setBluemapId(int communityId, String bluemapId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET bluemap_id = ? WHERE id = ?")) {
            ps.setString(1, bluemapId);
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    /** Владелец закрывает/открывает территорию для посторонних (ТЗ раздел, ответ на "закрытая терра") - зеркалится в TerritoryTitles вызывающим кодом (CommunityManager). */
    public synchronized void setClosed(int communityId, boolean closed) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET closed = ? WHERE id = ?")) {
            ps.setBoolean(1, closed);
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    /** Цвет клан-тега - однобуквенный код из {@link TagColors}, владелец выбирает в своей панели. */
    public synchronized void setTagColor(int communityId, String colorCode) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET tag_color = ? WHERE id = ?")) {
            ps.setString(1, colorCode);
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    /** Жирный ли клан-тег - владелец переключает кнопкой в своей панели. */
    public synchronized void setTagBold(int communityId, boolean bold) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_communities SET tag_bold = ? WHERE id = ?")) {
            ps.setBoolean(1, bold);
            ps.setInt(2, communityId);
            ps.executeUpdate();
        }
    }

    /** Каскадно удаляет и участников (FK ON DELETE CASCADE) - см. чек-лист удаления в ТЗ раздел 4.1. */
    public synchronized void deleteCommunity(int communityId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM territory_communities WHERE id = ?")) {
            ps.setInt(1, communityId);
            ps.executeUpdate();
        }
    }

    public synchronized Community getCommunity(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM territory_communities WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCommunity(rs) : null;
            }
        }
    }

    public synchronized Community getCommunityByName(String name) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM territory_communities WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCommunity(rs) : null;
            }
        }
    }

    /** "В какой общине состоит игрок X" - один индексированный запрос через community_members (ТЗ раздел 2). */
    public synchronized Community getCommunityForPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT c.* FROM territory_communities c "
                        + "JOIN territory_members m ON m.community_id = c.id WHERE m.uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCommunity(rs) : null;
            }
        }
    }

    public synchronized List<Community> listAllCommunities() throws SQLException {
        List<Community> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM territory_communities ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(readCommunity(rs));
        }
        return result;
    }

    /** Все связи "игрок -> id общины" одним запросом - для построения in-memory индекса участников в CommunityManager (не дёргать БД на каждый плейсхолдер, см. TerritoryPlaceholders). */
    public synchronized java.util.Map<UUID, Integer> allMemberships() throws SQLException {
        java.util.Map<UUID, Integer> result = new java.util.HashMap<>();
        try (PreparedStatement ps = connection().prepareStatement("SELECT community_id, uuid FROM territory_members");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(UUID.fromString(rs.getString("uuid")), rs.getInt("community_id"));
            }
        }
        return result;
    }

    /** Общины, чей прямоугольник пересекается с указанным (для проверки при подаче/одобрении заявки, ТЗ раздел 3.3/3.4). excludeId - своя же территория при заявке на расширение, null если не нужно исключать. */
    public synchronized List<Community> findOverlapping(String world, int x1, int z1, int x2, int z2, Integer excludeId) throws SQLException {
        List<Community> result = new ArrayList<>();
        for (Community c : listAllCommunities()) {
            if (excludeId != null && c.id() == excludeId) continue;
            if (c.overlaps(world, x1, z1, x2, z2)) result.add(c);
        }
        return result;
    }

    private Community readCommunity(ResultSet rs) throws SQLException {
        String restrictionsRaw = rs.getString("restrictions");
        Set<String> restrictions = new LinkedHashSet<>();
        if (restrictionsRaw != null && !restrictionsRaw.isBlank()) {
            for (String part : restrictionsRaw.split(",")) {
                if (!part.isBlank()) restrictions.add(part.trim());
            }
        }
        return new Community(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("tag"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getInt("x1"), rs.getInt("z1"), rs.getInt("x2"), rs.getInt("z2"),
                restrictions,
                rs.getBoolean("closed"),
                rs.getString("bluemap_id"),
                rs.getLong("created_at"),
                rs.getString("tag_color"),
                rs.getBoolean("tag_bold")
        );
    }

    // ---------- Members ----------

    public synchronized void addMember(int communityId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO territory_members (community_id, uuid) VALUES (?, ?)")) {
            ps.setInt(1, communityId);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void removeMember(int communityId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM territory_members WHERE community_id = ? AND uuid = ?")) {
            ps.setInt(1, communityId);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized List<UUID> getMembers(int communityId) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT uuid FROM territory_members WHERE community_id = ?")) {
            ps.setInt(1, communityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(UUID.fromString(rs.getString(1)));
            }
        }
        return result;
    }

    // ---------- Applications ----------

    public synchronized int insertApplication(CommunityApplication.Type type, UUID applicantUuid, String applicantDiscordId,
                                               Integer targetCommunityId, String proposedName, String proposedTag,
                                               String world, int x1, int z1, int x2, int z2, String description) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO territory_applications (type, applicant_uuid, applicant_discord_id, target_community_id, "
                        + "proposed_name, proposed_tag, world, x1, z1, x2, z2, description, status, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type.name());
            ps.setString(2, applicantUuid.toString());
            ps.setString(3, applicantDiscordId);
            if (targetCommunityId == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, targetCommunityId);
            ps.setString(5, proposedName);
            ps.setString(6, proposedTag);
            ps.setString(7, world);
            ps.setInt(8, x1);
            ps.setInt(9, z1);
            ps.setInt(10, x2);
            ps.setInt(11, z2);
            ps.setString(12, description);
            ps.setLong(13, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public synchronized void setApplicationAdminMessage(int applicationId, String channelId, String messageId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_applications SET admin_channel_id = ?, admin_message_id = ? WHERE id = ?")) {
            ps.setString(1, channelId);
            ps.setString(2, messageId);
            ps.setInt(3, applicationId);
            ps.executeUpdate();
        }
    }

    public synchronized void decideApplication(int applicationId, CommunityApplication.Status status, String decidedBy, String declineReason) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE territory_applications SET status = ?, decided_by = ?, decided_at = ?, decline_reason = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, decidedBy);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, declineReason);
            ps.setInt(5, applicationId);
            ps.executeUpdate();
        }
    }

    public synchronized CommunityApplication getApplication(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM territory_applications WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readApplication(rs) : null;
            }
        }
    }

    /** Есть ли у игрока заявка на рассмотрении (создание ИЛИ расширение - правило "1 заявка одновременно", ТЗ раздел 3.4). */
    public synchronized CommunityApplication getPendingApplicationForPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM territory_applications WHERE applicant_uuid = ? AND status = 'PENDING' LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readApplication(rs) : null;
            }
        }
    }

    private CommunityApplication readApplication(ResultSet rs) throws SQLException {
        Integer targetId = rs.getObject("target_community_id") == null ? null : rs.getInt("target_community_id");
        Long decidedAt = rs.getObject("decided_at") == null ? null : rs.getLong("decided_at");
        return new CommunityApplication(
                rs.getInt("id"),
                CommunityApplication.Type.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("applicant_uuid")),
                rs.getString("applicant_discord_id"),
                targetId,
                rs.getString("proposed_name"),
                rs.getString("proposed_tag"),
                rs.getString("world"),
                rs.getInt("x1"), rs.getInt("z1"), rs.getInt("x2"), rs.getInt("z2"),
                rs.getString("description"),
                CommunityApplication.Status.valueOf(rs.getString("status")),
                rs.getString("admin_channel_id"),
                rs.getString("admin_message_id"),
                rs.getString("decided_by"),
                decidedAt,
                rs.getString("decline_reason"),
                rs.getLong("submitted_at")
        );
    }

    // ---------- Meta (message ID persistence - список общин / панель, ТЗ раздел 4.3/6.3) ----------

    public synchronized String getMeta(String key) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT meta_value FROM territory_meta WHERE meta_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO territory_meta (meta_key, meta_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
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
