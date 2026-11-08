package com.sunshine.plugin.court;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.court.CourtModels.CourtCase;
import com.sunshine.plugin.court.CourtModels.Decree;
import com.sunshine.plugin.court.CourtModels.Fine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-слой суда: дела, штрафы, постановления. Та же MySQL, что player-db/общины (одно ленивое
 * переиспользуемое соединение, как {@code TerritoryDatabase}), поэтому таблицы автоматически
 * попадают и в автобэкап БД.
 */
public final class CourtDatabase {

    private final SunShinePlugin plugin;
    private Connection connection;

    public CourtDatabase(SunShinePlugin plugin) {
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
        plugin.getLogger().info("Court-БД подключена (таблицы court_*).");
    }

    private synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            connect();
        }
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS court_cases ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "plaintiff_discord VARCHAR(32) NOT NULL,"
                    + "plaintiff_name VARCHAR(32) NOT NULL,"
                    + "defendant_uuid CHAR(36) NOT NULL,"
                    + "defendant_name VARCHAR(32) NOT NULL,"
                    + "defendant_discord VARCHAR(32) NULL,"
                    + "title VARCHAR(100) NOT NULL,"
                    + "claim TEXT NOT NULL,"
                    + "evidence TEXT NULL,"
                    + "status VARCHAR(12) NOT NULL,"
                    + "judge_discord VARCHAR(32) NULL,"
                    + "hearing_at BIGINT NULL,"
                    + "channel_id VARCHAR(32) NULL,"
                    + "verdict VARCHAR(12) NULL,"
                    + "verdict_text TEXT NULL,"
                    + "verdict_at BIGINT NULL,"
                    + "appeal VARCHAR(10) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "closed_at BIGINT NULL,"
                    + "archive_given BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "KEY idx_status (status),"
                    + "KEY idx_plaintiff (plaintiff_discord),"
                    + "KEY idx_defendant (defendant_uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // таблица могла быть создана прошлой версией без этой колонки
            ensureColumn("court_cases", "archive_given", "BOOLEAN NOT NULL DEFAULT FALSE");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS court_fines ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "case_id INT NULL,"
                    + "target_uuid CHAR(36) NOT NULL,"
                    + "target_name VARCHAR(32) NOT NULL,"
                    + "target_discord VARCHAR(32) NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "reason VARCHAR(500) NOT NULL,"
                    + "issued_by VARCHAR(64) NOT NULL,"
                    + "status VARCHAR(12) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "deadline BIGINT NOT NULL,"
                    + "reminded BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "paid_at BIGINT NULL,"
                    + "appeal VARCHAR(10) NULL,"
                    + "KEY idx_target (target_uuid),"
                    + "KEY idx_status (status),"
                    + "KEY idx_case (case_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS court_decrees ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "case_id INT NULL,"
                    + "target_uuid CHAR(36) NOT NULL,"
                    + "target_name VARCHAR(32) NOT NULL,"
                    + "type VARCHAR(8) NOT NULL,"
                    + "duration VARCHAR(24) NULL,"
                    + "reason VARCHAR(500) NOT NULL,"
                    + "issued_by VARCHAR(64) NOT NULL,"
                    + "status VARCHAR(10) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "KEY idx_target (target_uuid),"
                    + "KEY idx_case (case_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

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
        }
    }

    // ---------- Дела ----------

    /** Дела с вердиктом, по которым судье ещё не выдали книгу-архив (выдаётся при входе судьи, если на момент вердикта он был не в игре). */
    public synchronized List<CourtCase> casesAwaitingArchive(String judgeDiscord) throws SQLException {
        List<CourtCase> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_cases WHERE judge_discord = ? AND verdict IS NOT NULL AND archive_given = FALSE")) {
            ps.setString(1, judgeDiscord);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readCase(rs));
            }
        }
        return result;
    }

    public synchronized void markArchiveGiven(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_cases SET archive_given = TRUE WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized int insertCase(String plaintiffDiscord, String plaintiffName, String defendantUuid,
                                       String defendantName, String defendantDiscord, String title, String claim,
                                       String evidence) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO court_cases (plaintiff_discord, plaintiff_name, defendant_uuid, defendant_name, "
                        + "defendant_discord, title, claim, evidence, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'FILED', ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, plaintiffDiscord);
            ps.setString(2, plaintiffName);
            ps.setString(3, defendantUuid);
            ps.setString(4, defendantName);
            ps.setString(5, defendantDiscord);
            ps.setString(6, title);
            ps.setString(7, claim);
            ps.setString(8, evidence);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public synchronized CourtCase getCase(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM court_cases WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCase(rs) : null;
            }
        }
    }

    public synchronized int countOpenCases(String plaintiffDiscord) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COUNT(*) FROM court_cases WHERE plaintiff_discord = ? AND status NOT IN ('CLOSED', 'REJECTED')")) {
            ps.setString(1, plaintiffDiscord);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Атомарный переход статуса: true, если дело было в одном из from-статусов и теперь в to (защита от двойного клика двух судей). */
    public synchronized boolean transitionCase(int id, String to, String... from) throws SQLException {
        return transition("court_cases", id, to, from);
    }

    public synchronized void assignJudge(int id, String judgeDiscord, String channelId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE court_cases SET judge_discord = ?, channel_id = ? WHERE id = ?")) {
            ps.setString(1, judgeDiscord);
            ps.setString(2, channelId);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    public synchronized void setHearing(int id, long at) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_cases SET hearing_at = ? WHERE id = ?")) {
            ps.setLong(1, at);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public synchronized void setVerdict(int id, String verdict, String text) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE court_cases SET verdict = ?, verdict_text = ?, verdict_at = ? WHERE id = ?")) {
            ps.setString(1, verdict);
            ps.setString(2, text);
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, id);
            ps.executeUpdate();
        }
    }

    public synchronized void setCaseAppeal(int id, String appeal) throws SQLException {
        setAppeal("court_cases", id, appeal);
    }

    public synchronized void setCaseVerdictOnly(int id, String verdict) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_cases SET verdict = ? WHERE id = ?")) {
            ps.setString(1, verdict);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public synchronized void markCaseClosed(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_cases SET closed_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public synchronized List<CourtCase> casesDueForHearing(long now) throws SQLException {
        List<CourtCase> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_cases WHERE status = 'SCHEDULED' AND hearing_at IS NOT NULL AND hearing_at <= ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readCase(rs));
            }
        }
        return result;
    }

    public synchronized List<CourtCase> casesForDiscords(List<String> discordIds, int limit) throws SQLException {
        List<CourtCase> result = new ArrayList<>();
        if (discordIds.isEmpty()) return result;
        String marks = String.join(",", java.util.Collections.nCopies(discordIds.size(), "?"));
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_cases WHERE plaintiff_discord IN (" + marks + ") OR defendant_discord IN (" + marks
                        + ") ORDER BY id DESC LIMIT " + limit)) {
            int i = 1;
            for (int pass = 0; pass < 2; pass++) {
                for (String d : discordIds) ps.setString(i++, d);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readCase(rs));
            }
        }
        return result;
    }

    public synchronized List<CourtCase> casesAgainst(String defendantUuid, int limit) throws SQLException {
        List<CourtCase> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_cases WHERE defendant_uuid = ? ORDER BY id DESC LIMIT " + limit)) {
            ps.setString(1, defendantUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readCase(rs));
            }
        }
        return result;
    }

    private CourtCase readCase(ResultSet rs) throws SQLException {
        return new CourtCase(
                rs.getInt("id"),
                rs.getString("plaintiff_discord"),
                rs.getString("plaintiff_name"),
                rs.getString("defendant_uuid"),
                rs.getString("defendant_name"),
                rs.getString("defendant_discord"),
                rs.getString("title"),
                rs.getString("claim"),
                rs.getString("evidence"),
                rs.getString("status"),
                rs.getString("judge_discord"),
                nullableLong(rs, "hearing_at"),
                rs.getString("channel_id"),
                rs.getString("verdict"),
                rs.getString("verdict_text"),
                nullableLong(rs, "verdict_at"),
                rs.getString("appeal"),
                rs.getLong("created_at"));
    }

    // ---------- Штрафы ----------

    public synchronized int insertFine(Integer caseId, String targetUuid, String targetName, String targetDiscord,
                                       long amount, String reason, String issuedBy, long deadline) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO court_fines (case_id, target_uuid, target_name, target_discord, amount, reason, issued_by, "
                        + "status, created_at, deadline) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (caseId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, caseId);
            ps.setString(2, targetUuid);
            ps.setString(3, targetName);
            ps.setString(4, targetDiscord);
            ps.setLong(5, amount);
            ps.setString(6, reason);
            ps.setString(7, issuedBy);
            ps.setLong(8, System.currentTimeMillis());
            ps.setLong(9, deadline);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public synchronized Fine getFine(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM court_fines WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readFine(rs) : null;
            }
        }
    }

    public synchronized boolean transitionFine(int id, String to, String... from) throws SQLException {
        return transition("court_fines", id, to, from);
    }

    public synchronized void markFinePaid(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE court_fines SET status = 'PAID', paid_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public synchronized void markFineReminded(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_fines SET reminded = TRUE WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized void setFineAppeal(int id, String appeal) throws SQLException {
        setAppeal("court_fines", id, appeal);
    }

    public synchronized List<Fine> overdueFines(long now) throws SQLException {
        return finesWhere("status = 'PENDING' AND deadline <= ?", now);
    }

    public synchronized List<Fine> finesNeedingReminder(long now, long windowMs) throws SQLException {
        List<Fine> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_fines WHERE status = 'PENDING' AND reminded = FALSE AND deadline > ? AND deadline - ? <= ?")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, windowMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readFine(rs));
            }
        }
        return result;
    }

    public synchronized List<Fine> finesForCase(int caseId) throws SQLException {
        return finesWhere("case_id = ?", caseId);
    }

    public synchronized List<Fine> finesForTargets(List<String> targetUuids, int limit) throws SQLException {
        List<Fine> result = new ArrayList<>();
        if (targetUuids.isEmpty()) return result;
        String marks = String.join(",", java.util.Collections.nCopies(targetUuids.size(), "?"));
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_fines WHERE target_uuid IN (" + marks + ") ORDER BY id DESC LIMIT " + limit)) {
            int i = 1;
            for (String u : targetUuids) ps.setString(i++, u);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readFine(rs));
            }
        }
        return result;
    }

    public synchronized int countUnpaidFines(String targetUuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COUNT(*) FROM court_fines WHERE target_uuid = ? AND status IN ('PENDING', 'OVERDUE')")) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<Fine> finesWhere(String where, long param) throws SQLException {
        List<Fine> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM court_fines WHERE " + where)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readFine(rs));
            }
        }
        return result;
    }

    private Fine readFine(ResultSet rs) throws SQLException {
        Integer caseId = rs.getObject("case_id") == null ? null : rs.getInt("case_id");
        return new Fine(
                rs.getInt("id"),
                caseId,
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("target_discord"),
                rs.getLong("amount"),
                rs.getString("reason"),
                rs.getString("issued_by"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("deadline"),
                rs.getBoolean("reminded"),
                nullableLong(rs, "paid_at"),
                rs.getString("appeal"));
    }

    // ---------- Постановления ----------

    public synchronized int insertDecree(Integer caseId, String targetUuid, String targetName, String type,
                                         String duration, String reason, String issuedBy) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO court_decrees (case_id, target_uuid, target_name, type, duration, reason, issued_by, "
                        + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)", Statement.RETURN_GENERATED_KEYS)) {
            if (caseId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, caseId);
            ps.setString(2, targetUuid);
            ps.setString(3, targetName);
            ps.setString(4, type);
            ps.setString(5, duration);
            ps.setString(6, reason);
            ps.setString(7, issuedBy);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public synchronized Decree getDecree(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM court_decrees WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readDecree(rs) : null;
            }
        }
    }

    public synchronized List<Decree> decreesForCase(int caseId) throws SQLException {
        return decreesWhere("case_id = ?", caseId);
    }

    public synchronized List<Decree> decreesForTarget(String targetUuid, int limit) throws SQLException {
        List<Decree> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM court_decrees WHERE target_uuid = ? ORDER BY id DESC LIMIT " + limit)) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readDecree(rs));
            }
        }
        return result;
    }

    public synchronized void markDecreeRevoked(int id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE court_decrees SET status = 'REVOKED' WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private List<Decree> decreesWhere(String where, long param) throws SQLException {
        List<Decree> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM court_decrees WHERE " + where)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readDecree(rs));
            }
        }
        return result;
    }

    private Decree readDecree(ResultSet rs) throws SQLException {
        Integer caseId = rs.getObject("case_id") == null ? null : rs.getInt("case_id");
        return new Decree(
                rs.getInt("id"),
                caseId,
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("type"),
                rs.getString("duration"),
                rs.getString("reason"),
                rs.getString("issued_by"),
                rs.getString("status"),
                rs.getLong("created_at"));
    }

    // ---------- Общее ----------

    private boolean transition(String table, int id, String to, String... from) throws SQLException {
        String marks = String.join(",", java.util.Collections.nCopies(from.length, "?"));
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE " + table + " SET status = ? WHERE id = ? AND status IN (" + marks + ")")) {
            ps.setString(1, to);
            ps.setInt(2, id);
            for (int i = 0; i < from.length; i++) ps.setString(3 + i, from[i]);
            return ps.executeUpdate() > 0;
        }
    }

    private void setAppeal(String table, int id, String appeal) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("UPDATE " + table + " SET appeal = ? WHERE id = ?")) {
            ps.setString(1, appeal);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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
