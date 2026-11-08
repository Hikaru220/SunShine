package com.sunshine.plugin.backup;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.config.SunShineConfig;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Автобэкап MySQL-базы (той же, что player-db: FlectonePulse, общины, sunshine_players) в сжатые
 * SQL-файлы на диск сервера. Свой JDBC-дамп без mysqldump - на панельном хостинге бинарников
 * MySQL нет. Восстановление: phpMyAdmin -> база -> Импорт -> .sql.gz.
 * <p>
 * Таймер тикает раз в 30 минут и делает бэкап, только если самому свежему файлу уже больше
 * interval-hours - так частые рестарты сервера не плодят лишних бэкапов.
 */
public final class DatabaseBackupService {

    private static final long CHECK_PERIOD_TICKS = 30L * 60L * 20L;
    private static final long FIRST_CHECK_DELAY_TICKS = 5L * 60L * 20L;
    private static final int ROWS_PER_INSERT = 200;
    private static final String FILE_SUFFIX = ".sql.gz";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SunShinePlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask task;

    public DatabaseBackupService(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, FIRST_CHECK_DELAY_TICKS, CHECK_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        SunShineConfig cfg = plugin.config();
        if (!cfg.backupEnabled()) return;
        File newest = newestBackup();
        long intervalMs = Math.max(1, cfg.backupIntervalHours()) * 3_600_000L;
        if (newest != null && System.currentTimeMillis() - newest.lastModified() < intervalMs) return;
        try {
            runNow();
        } catch (Exception e) {
            plugin.getLogger().warning("Бэкап БД не удался: " + e.getMessage());
        }
    }

    /** Блокирующий дамп всей базы - звать только с асинхронного потока. Возвращает готовый файл. */
    public File runNow() throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("бэкап уже выполняется");
        }
        try {
            SunShineConfig cfg = plugin.config();
            File dir = backupDir();
            Files.createDirectories(dir.toPath());

            String db = cfg.playerDbName();
            String base = db + "_" + LocalDateTime.now().format(STAMP) + FILE_SUFFIX;
            File part = new File(dir, base + ".part");
            File out = new File(dir, base);

            long started = System.currentTimeMillis();
            int tableCount;
            try (Connection c = open(cfg);
                 Writer w = new BufferedWriter(new OutputStreamWriter(
                         new GZIPOutputStream(new FileOutputStream(part)), StandardCharsets.UTF_8))) {
                tableCount = dump(c, db, w);
            } catch (Exception e) {
                part.delete();
                throw e;
            }
            if (tableCount == 0) {
                // Пустая база (сброшена хостом) - такой "бэкап" не сохраняем и ничего не ротируем,
                // иначе через keep-hours он вытеснил бы последние хорошие копии.
                part.delete();
                throw new IllegalStateException("в базе " + db + " нет ни одной таблицы - бэкап пропущен, старые копии не тронуты");
            }
            Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);

            rotate(cfg.backupKeepHours());
            plugin.getLogger().info("Бэкап БД готов: " + out.getName() + " (" + tableCount + " таблиц, "
                    + out.length() / 1024 + " КБ, " + (System.currentTimeMillis() - started) + " мс).");
            uploadToDiscord(out);
            return out;
        } finally {
            running.set(false);
        }
    }

    private File backupDir() {
        return new File(plugin.getDataFolder(), plugin.config().backupDirectory());
    }

    private Connection open(SunShineConfig cfg) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://" + cfg.playerDbHost() + ":" + cfg.playerDbPort() + "/" + cfg.playerDbName()
                + "?useUnicode=true&characterEncoding=utf8&connectTimeout=10000";
        return DriverManager.getConnection(url, cfg.playerDbUser(), cfg.playerDbPassword());
    }

    private int dump(Connection c, String db, Writer w) throws Exception {
        w.write("-- SunShine database backup\n-- database: " + db + "\n-- created: " + Instant.now() + "\n\n");
        w.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\nSET UNIQUE_CHECKS=0;\nSET SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n\n");

        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW FULL TABLES WHERE Table_type = 'BASE TABLE'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        for (String table : tables) {
            dumpTable(c, table, w);
        }
        w.write("SET FOREIGN_KEY_CHECKS=1;\nSET UNIQUE_CHECKS=1;\n");
        return tables.size();
    }

    private void dumpTable(Connection c, String table, Writer w) throws Exception {
        String quoted = "`" + table.replace("`", "``") + "`";
        w.write("DROP TABLE IF EXISTS " + quoted + ";\n");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + quoted)) {
            rs.next();
            w.write(rs.getString(2) + ";\n\n");
        }

        try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(Integer.MIN_VALUE); // потоковое чтение - большие таблицы не грузим целиком в память
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + quoted)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                int inBatch = 0;
                while (rs.next()) {
                    w.write(inBatch == 0 ? "INSERT INTO " + quoted + " VALUES\n(" : ",\n(");
                    for (int i = 1; i <= cols; i++) {
                        if (i > 1) w.write(",");
                        w.write(literal(rs, md, i));
                    }
                    w.write(")");
                    if (++inBatch >= ROWS_PER_INSERT) {
                        w.write(";\n");
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) w.write(";\n");
            }
        }
        w.write("\n");
    }

    private String literal(ResultSet rs, ResultSetMetaData md, int i) throws Exception {
        switch (md.getColumnType(i)) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                return hex(rs.getBytes(i));
            }
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> {
                // getString, а не getObject - не конвертируем часовые пояса и не падаем на нулевых датах.
                String s = rs.getString(i);
                return s == null ? "NULL" : quote(s);
            }
            default -> {
                Object o = rs.getObject(i);
                if (o == null) return "NULL";
                if (o instanceof Boolean b) return b ? "1" : "0";
                if (o instanceof java.math.BigDecimal d) return d.toPlainString();
                if (o instanceof Number) return o.toString();
                if (o instanceof byte[] bytes) return hex(bytes);
                return quote(o.toString());
            }
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "NULL";
        if (bytes.length == 0) return "''";
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2).append("0x");
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\0' -> sb.append("\\0");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\u001a' -> sb.append("\\Z");
                default -> sb.append(ch);
            }
        }
        return sb.append('\'').toString();
    }

    private File[] listBackups() {
        File[] files = backupDir().listFiles((d, name) -> name.endsWith(FILE_SUFFIX));
        return files == null ? new File[0] : files;
    }

    private File newestBackup() {
        return Arrays.stream(listBackups()).max(Comparator.comparingLong(File::lastModified)).orElse(null);
    }

    /** Удаляет бэкапы старше keepHours. Самый свежий не трогаем никогда - если бэкапы долго не получались, не должно остаться ни одного. */
    private void rotate(int keepHours) {
        File[] files = listBackups();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long cutoff = System.currentTimeMillis() - Math.max(1, keepHours) * 3_600_000L;
        for (int i = 1; i < files.length; i++) {
            if (files[i].lastModified() < cutoff && !files[i].delete()) {
                plugin.getLogger().warning("Бэкап БД: не удалось удалить старый файл " + files[i].getName());
            }
        }
    }

    /** Копия вне хостинга: в дампе есть IP игроков, поэтому канал должен быть закрытым. Лимит файла - как у сервера Discord. */
    private void uploadToDiscord(File file) {
        String channelId = plugin.config().backupDiscordChannelId();
        if (channelId.isBlank()) return;
        if (!plugin.discordBot().isReady()) {
            plugin.getLogger().warning("Бэкап БД: Discord ещё не подключён - копия в канал не отправлена.");
            return;
        }
        TextChannel channel = plugin.discordBot().jda().getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Бэкап БД: канал backup.discord-channel-id=" + channelId + " не найден - копия не отправлена.");
            return;
        }
        long limit = channel.getGuild().getMaxFileSize();
        if (file.length() > limit) {
            plugin.getLogger().warning("Бэкап БД: файл " + file.length() / 1024 + " КБ больше лимита Discord ("
                    + limit / 1024 + " КБ) - копия не отправлена.");
            return;
        }
        channel.sendMessage("Бэкап БД `" + file.getName() + "` (" + file.length() / 1024 + " КБ)")
                .addFiles(FileUpload.fromData(file))
                .queue(null, t -> plugin.getLogger().warning("Бэкап БД: не удалось отправить копию в Discord: " + t.getMessage()));
    }
}
