package com.sunshine.plugin.migration;

import com.sunshine.plugin.SunShinePlugin;
import org.bukkit.command.CommandSender;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Разовый перенос данных FlectonePulse из встроенной H2 (локальный файл) в MySQL - чтобы SunShine
 * мог безопасно (по сети, без риска для живого файла) читать историю наказаний для /profile.
 * <p>
 * <b>Запускать только когда jar FlectonePulse убран из plugins/ и сервер перезапущен без него</b> -
 * иначе H2-файл занят самим FlectonePulse (embedded-режим H2 не рассчитан на второе подключение
 * одновременно, конфликт может испортить файл). После переноса: вернуть jar FlectonePulse, в его
 * config.yml поставить {@code database.type: MySQL} с теми же данными подключения, что здесь, и
 * перезапустить сервер ещё раз.
 * <p>
 * Целевая MySQL - та же база, что у {@code player-db} (SunShine): у таблиц FlectonePulse свой
 * префикс {@code fp_}, коллизий с {@code sunshine_players} нет, отдельный слот БД не нужен.
 */
public final class FlectonePulseMigrator {

    /** Порядок важен - таблицы с внешними ключами переносятся после тех, на кого они ссылаются. */
    private static final String[] TABLE_ORDER = {
            "fp_player", "fp_fcolor", "fp_time", "fp_setting", "fp_mail",
            "fp_ignore", "fp_moderation", "fp_player_fcolor", "fp_version"
    };

    /** Точная схема из официального репозитория FlectonePulse (api/src/main/resources/sqls/mysql.sql). */
    private static final String SCHEMA_SQL = """
            CREATE TABLE IF NOT EXISTS `fp_player` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `online` TINYINT NOT NULL DEFAULT 0,
                `uuid` VARCHAR(36) NOT NULL UNIQUE,
                `name` VARCHAR(255) NOT NULL,
                `ip` VARCHAR(39),
                UNIQUE(`uuid`, `name`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_time` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `player` INTEGER NOT NULL UNIQUE,
                `first` BIGINT NOT NULL,
                `last` BIGINT NOT NULL,
                `total` BIGINT NOT NULL,
                `sessions` INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(`player`) REFERENCES `fp_player`(`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_setting` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `player` INTEGER NOT NULL,
                `type` VARCHAR(255) NOT NULL,
                `value` TEXT,
                FOREIGN KEY(`player`) REFERENCES `fp_player`(`id`),
                UNIQUE(`player`, `type`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_mail` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `date` BIGINT NOT NULL,
                `sender` INTEGER NOT NULL,
                `receiver` INTEGER NOT NULL,
                `message` TEXT NOT NULL,
                `valid` TINYINT NOT NULL DEFAULT 1,
                FOREIGN KEY(`sender`) REFERENCES `fp_player`(`id`),
                FOREIGN KEY(`receiver`) REFERENCES `fp_player`(`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_ignore` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `date` BIGINT NOT NULL,
                `initiator` INTEGER NOT NULL,
                `target` INTEGER NOT NULL,
                `valid` TINYINT NOT NULL DEFAULT 1,
                FOREIGN KEY(`initiator`) REFERENCES `fp_player`(`id`),
                FOREIGN KEY(`target`) REFERENCES `fp_player`(`id`),
                UNIQUE(`initiator`, `target`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_moderation` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `player` INTEGER NOT NULL,
                `date` BIGINT NOT NULL,
                `time` BIGINT NOT NULL,
                `reason` TEXT,
                `moderator` INTEGER NOT NULL,
                `type` VARCHAR(255) NOT NULL,
                `valid` TINYINT NOT NULL DEFAULT 1,
                `server` VARCHAR(255),
                FOREIGN KEY(`player`) REFERENCES `fp_player`(`id`),
                FOREIGN KEY(`moderator`) REFERENCES `fp_player`(`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_fcolor` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `name` VARCHAR(255) NOT NULL UNIQUE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_player_fcolor` (
                `id` INTEGER PRIMARY KEY AUTO_INCREMENT,
                `number` INTEGER NOT NULL,
                `player` INTEGER NOT NULL,
                `fcolor` INTEGER NOT NULL,
                `type` VARCHAR(255) NOT NULL,
                FOREIGN KEY(`player`) REFERENCES `fp_player`(`id`),
                FOREIGN KEY(`fcolor`) REFERENCES `fp_fcolor`(`id`),
                UNIQUE(`number`, `player`, `type`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            ---
            CREATE TABLE IF NOT EXISTS `fp_version` (
                `id` INTEGER PRIMARY KEY CHECK (`id` = 1),
                `name` VARCHAR(32) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """;

    private final SunShinePlugin plugin;

    public FlectonePulseMigrator(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Выполняет перенос. Полностью синхронный и блокирующий - вызывающий код должен сам увести
     * его на асинхронный поток (JDBC/файловый ввод-вывод, не трогает основной поток сервера).
     *
     * @param h2FilePath путь к H2-файлу БЕЗ расширения {@code .mv.db}
     * @param h2User     пользователь H2 (обычно "sa", если FlectonePulse не задавал свой)
     * @param h2Password пароль H2 (обычно пустой)
     * @param feedback   куда слать прогресс построчно
     */
    public void migrate(String h2FilePath, String h2User, String h2Password, CommandSender feedback) {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            feedback.sendMessage("H2-драйвер не загружен (проверь libraries в plugin.yml): " + e.getMessage());
            return;
        }

        // IFEXISTS=TRUE - не создавать новый файл, если по пути ничего нет (защита от опечатки в пути,
        // которая иначе тихо создала бы пустую БД вместо переноса реальной).
        String h2Url = "jdbc:h2:file:" + h2FilePath + ";IFEXISTS=TRUE";

        try (Connection h2 = DriverManager.getConnection(h2Url, h2User, h2Password)) {
            feedback.sendMessage("Подключился к H2: " + h2FilePath);

            var db = plugin.playerDatabase();
            if (db == null) {
                feedback.sendMessage("player-db не подключена в SunShine - нужна живая MySQL-БД как цель переноса.");
                return;
            }

            try (Connection mysql = openMysqlConnection()) {
                mysql.setAutoCommit(false);

                feedback.sendMessage("Создаю таблицы FlectonePulse в MySQL (если их ещё нет)...");
                createSchema(mysql);

                for (String table : TABLE_ORDER) {
                    int copied = copyTable(h2, mysql, table, feedback);
                    fixAutoIncrement(mysql, table);
                    feedback.sendMessage("  " + table + ": перенесено строк — " + copied);
                }

                mysql.commit();
                feedback.sendMessage("Готово! Теперь: верни jar FlectonePulse, поставь в его config.yml "
                        + "database.type: MySQL с теми же данными подключения, что у player-db в SunShine, и перезапусти сервер.");
            }
        } catch (SQLException e) {
            feedback.sendMessage("Ошибка переноса: " + e.getMessage());
            plugin.getLogger().warning("Перенос FlectonePulse H2 -> MySQL не удался: " + e.getMessage());
        }
    }

    private Connection openMysqlConnection() throws SQLException {
        String url = "jdbc:mysql://" + plugin.config().playerDbHost() + ":" + plugin.config().playerDbPort()
                + "/" + plugin.config().playerDbName()
                + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true";
        return DriverManager.getConnection(url, plugin.config().playerDbUser(), plugin.config().playerDbPassword());
    }

    private void createSchema(Connection mysql) throws SQLException {
        try (Statement statement = mysql.createStatement()) {
            for (String ddl : SCHEMA_SQL.split("---")) {
                String trimmed = ddl.trim();
                if (!trimmed.isEmpty()) statement.executeUpdate(trimmed);
            }
        }
        mysql.commit();
    }

    /** Копирует все строки одной таблицы из H2 в MySQL, сохраняя все колонки как есть (включая id). */
    private int copyTable(Connection h2, Connection mysql, String table, CommandSender feedback) throws SQLException {
        int copied = 0;
        try (Statement select = h2.createStatement();
             ResultSet rs = select.executeQuery("SELECT * FROM " + table)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            Map<Integer, String> columnNames = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                columnNames.put(i, meta.getColumnName(i));
            }

            String columnsCsv = String.join(", ", columnNames.values());
            String placeholders = "?" + ", ?".repeat(columnCount - 1);
            String insertSql = "INSERT IGNORE INTO " + table + " (" + columnsCsv + ") VALUES (" + placeholders + ")";

            try (PreparedStatement insert = mysql.prepareStatement(insertSql)) {
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        insert.setObject(i, rs.getObject(i));
                    }
                    insert.addBatch();
                    copied++;
                    if (copied % 500 == 0) {
                        insert.executeBatch();
                        feedback.sendMessage("  " + table + ": ... " + copied);
                    }
                }
                insert.executeBatch();
            }
        }
        return copied;
    }

    /**
     * Чтобы новые строки, которые FlectonePulse создаст после переезда, не наступали на перенесённые
     * id. MySQL не принимает подзапрос прямо в ALTER TABLE ... AUTO_INCREMENT = (...), поэтому сначала
     * считаем MAX(id) отдельным запросом, потом подставляем уже готовое число.
     */
    private void fixAutoIncrement(Connection mysql, String table) throws SQLException {
        long nextId;
        try (Statement select = mysql.createStatement();
             ResultSet rs = select.executeQuery("SELECT MAX(id) FROM " + table)) {
            rs.next();
            nextId = rs.getLong(1) + 1; // 0+1=1 и для пустой/только что созданной таблицы - тоже ок
        }
        try (Statement alter = mysql.createStatement()) {
            alter.executeUpdate("ALTER TABLE " + table + " AUTO_INCREMENT = " + nextId);
        } catch (SQLException ignored) {
            // у fp_version нет AUTO_INCREMENT (PRIMARY KEY CHECK id=1) - это ожидаемо, пропускаем
        }
    }
}
