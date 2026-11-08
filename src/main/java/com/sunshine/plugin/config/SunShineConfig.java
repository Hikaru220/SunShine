package com.sunshine.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

public class SunShineConfig {

    private final FileConfiguration config;

    public SunShineConfig(FileConfiguration config) {
        this.config = config;
    }

    public String botToken() {
        return config.getString("bot.token", "");
    }

    /** Скрывать из консоли повторяющиеся ошибки сертификата шлюза Discord при старте (одна строка вместо стектрейсов). */
    public boolean hideGatewayCertNoise() {
        return config.getBoolean("network.hide-gateway-cert-noise", true);
    }

    /** Закреплённые адреса Discord для REST-запросов ботов (обход плохого DNS хостинга). По умолчанию включено. */
    public boolean pinDiscordIps() {
        return config.getBoolean("network.pin-discord-ips", true);
    }

    public String botStatus() {
        return config.getString("bot.status", "{online}/{max} игроков онлайн");
    }

    public String chatChannelId() {
        return config.getString("channels.chat", "");
    }

    public String eventsChannelId() {
        String events = config.getString("channels.events", "");
        return (events == null || events.isBlank()) ? chatChannelId() : events;
    }

    public String linkChannelId() {
        return config.getString("channels.link", "");
    }

    public String applicationsReviewChannelId() {
        return config.getString("channels.applications-review", "");
    }

    /** ID Discord-канала для лога всех наказаний (бан/мут/кик/варн через /profile или /sunshine punish). Пусто = не логировать. */
    public String moderationLogChannelId() {
        return config.getString("channels.moderation-log", "");
    }

    public boolean minecraftToDiscordEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.enabled", true);
    }

    public boolean joinEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.join.enabled", true);
    }

    public String joinFormat() {
        return config.getString("sync.minecraft-to-discord.join.format", "**{player}** зашёл на сервер");
    }

    /** Слать сообщение о входе через webhook (ник+аватар игрока), а не от лица бота. */
    public boolean joinWebhookEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.join.webhook", false);
    }

    public boolean leaveEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.leave.enabled", true);
    }

    public String leaveFormat() {
        return config.getString("sync.minecraft-to-discord.leave.format", "**{player}** покинул сервер");
    }

    /** Слать сообщение о выходе через webhook (ник+аватар игрока), а не от лица бота. */
    public boolean leaveWebhookEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.leave.webhook", false);
    }

    public boolean deathEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.death.enabled", true);
    }

    public String deathFormat() {
        return config.getString("sync.minecraft-to-discord.death.format", "☠ {message}");
    }

    /** Слать сообщение о смерти через webhook (ник+аватар погибшего), а не от лица бота. */
    public boolean deathWebhookEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.death.webhook", false);
    }

    public boolean chatToDiscordEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.chat.enabled", true);
    }

    public String chatToDiscordFormat() {
        return config.getString("sync.minecraft-to-discord.chat.format", "**{player}**: {message}");
    }

    /**
     * If true, chat messages are relayed to Discord through a channel webhook (player's name +
     * skin-head avatar) instead of a normal bot message. Requires the bot to have "Manage
     * Webhooks" on the chat channel.
     */
    public boolean chatWebhookEnabled() {
        return config.getBoolean("sync.minecraft-to-discord.chat.webhook.enabled", false);
    }

    public boolean discordToMinecraftEnabled() {
        return config.getBoolean("sync.discord-to-minecraft.enabled", true);
    }

    public boolean discordChatEnabled() {
        return config.getBoolean("sync.discord-to-minecraft.chat.enabled", true);
    }

    public String discordChatFormat() {
        return config.getString("sync.discord-to-minecraft.chat.format",
                "<gray>[<blue>Discord<gray>]</gray> <white><member></white>: <message>");
    }

    public int linkCodeExpiryMinutes() {
        return config.getInt("linking.code-expiry-minutes", 10);
    }

    public boolean notifyOnLinkEnabled() {
        return config.getBoolean("linking.notify-on-link.enabled", false);
    }

    public String notifyOnLinkFormat() {
        return config.getString("linking.notify-on-link.format", "**{player}** привязал(а) Discord аккаунт");
    }

    /**
     * If true, a linked player's Discord server nickname is set to their current Minecraft
     * nickname every time they join the server.
     */
    public boolean syncNicknameOnJoin() {
        return config.getBoolean("linking.sync-nickname-on-join", true);
    }

    public boolean consoleEnabled() {
        return config.getBoolean("console.enabled", false);
    }

    /** ID Discord-канала, в который транслируется консоль и из которого принимаются команды. */
    public String consoleChannelId() {
        return config.getString("console.channel-id", "");
    }

    /**
     * ID Discord-ролей, участникам которых разрешено выполнять команды консоли сервера через
     * console.channel-id. Пусто = никакие команды из Discord не выполняются вообще (fail-closed).
     * Поддерживается и старый одиночный ключ console.command-role-id для обратной совместимости.
     */
    public java.util.List<String> consoleCommandRoleIds() {
        java.util.List<String> ids = config.getStringList("console.command-role-ids");
        if (!ids.isEmpty()) return ids;
        String legacy = config.getString("console.command-role-id", "");
        return legacy.isBlank() ? java.util.List.of() : java.util.List.of(legacy);
    }

    /**
     * Включает одностороннюю синхронизацию групп LuckPerms -&gt; Discord роли.
     * Направление ТОЛЬКО LuckPerms -&gt; Discord, обратной синхронизации нет.
     */
    public boolean groupSyncEnabled() {
        return config.getBoolean("group-sync.enabled", false);
    }

    /** Маппинг "группа LuckPerms (в нижнем регистре) -&gt; Discord role id" из group-sync.mappings. */
    public java.util.Map<String, String> groupSyncMappings() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("group-sync.mappings");
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            String roleId = section.getString(key);
            if (roleId != null && !roleId.isBlank()) {
                result.put(key.toLowerCase(java.util.Locale.ROOT), roleId);
            }
        }
        return result;
    }

    public boolean requireLink() {
        return config.getBoolean("auth.require-link", false);
    }

    public String kickNotLinkedMessage() {
        return config.getString("auth.kick-message",
                "&cНе удалось подключиться к серверу&7: &9SunShineAuth\n\n" +
                        "&fТвой код: &b{code}\n" +
                        "&7Напиши его боту &9{bot}&7 в личные сообщения (ЛС)\n" +
                        "&7Напиши в ЛС только код: &b{code}");
    }

    public boolean requireLoginConfirmation() {
        return config.getBoolean("auth.require-login-confirmation", false);
    }

    public int loginConfirmationTimeoutSeconds() {
        return config.getInt("auth.login-confirmation-timeout-seconds", 60);
    }

    public boolean applicationsAutoWhitelist() {
        return config.getBoolean("applications.auto-whitelist", true);
    }

    /**
     * Console command run (as the console) to whitelist an accepted applicant, with {player}
     * replaced by their Minecraft nickname. Blank means use Bukkit's built-in whitelist API instead.
     */
    public String applicationsWhitelistCommand() {
        return config.getString("applications.whitelist-command", "whitelist add {player}");
    }

    /**
     * Discord role ID given to the applicant when their application is accepted. Blank = no role given.
     */
    public String applicationsAcceptedRoleId() {
        return config.getString("applications.accepted-role-id", "");
    }

    /**
     * Discord role ID given only once the accepted applicant actually joins the Minecraft
     * server for the first time. Blank = no second role given.
     */
    public String applicationsJoinRoleId() {
        return config.getString("applications.join-role-id", "");
    }

    /**
     * Команда убрать из белого списка при "Рассмотреть заново" после одобрения. Пусто в конфиге =
     * берём applications.whitelist-command и меняем " add " на " remove "; если не получилось - Bukkit-API.
     */
    public String applicationsWhitelistRemoveCommand() {
        String explicit = config.getString("applications.whitelist-remove-command", "");
        if (explicit != null && !explicit.isBlank()) return explicit;
        String add = applicationsWhitelistCommand();
        return add != null && add.contains(" add ") ? add.replace(" add ", " remove ") : "";
    }

    // --- Приватные каналы заявок (applications.rooms.*) ---

    /** true = после анкеты создаётся отдельный канал заявки; false = старый режим (карточка в channels.applications-review + ЛС). */
    public boolean applicationsRoomsEnabled() {
        return config.getBoolean("applications.rooms.enabled", true);
    }

    public String applicationsRoomsCategoryId() {
        return config.getString("applications.rooms.category-id", "");
    }

    /** Роли, видящие каналы заявок и принимающие/отклоняющие их (вместе с правом "Управление сервером"). */
    public java.util.List<String> applicationsReviewerRoleIds() {
        return config.getStringList("applications.rooms.reviewer-role-ids");
    }

    /** Пинговать ли роли-модераторов в карточке новой заявки (по умолчанию нет - они и так видят канал). */
    public boolean applicationsPingReviewers() {
        return config.getBoolean("applications.rooms.ping-reviewers", false);
    }

    /** Роль, которая снимается с игрока при одобрении заявки (например "new"). Пусто = не снимать. */
    public String applicationsRemoveRoleId() {
        return config.getString("applications.rooms.remove-role-id", "");
    }

    public String applicationsServerIp() {
        return config.getString("applications.rooms.server-ip", "play.sunshine-mc.ru");
    }

    /** Свой баннер под анкетой, путь относительно папки плагина. Пусто = встроенный. */
    public String applicationsBannerFile() {
        return config.getString("applications.rooms.banner-file", "");
    }

    public String applicationsWebhookName() {
        return config.getString("applications.rooms.webhook-name", "SunShine | Проходка");
    }

    /** Аватар вебхука одобрения/отклонения. Пусто = аватар бота. */
    public String applicationsWebhookAvatarUrl() {
        return config.getString("applications.rooms.webhook-avatar-url", "");
    }

    public String applicationsGreeting() {
        return config.getString("applications.rooms.greeting",
                "Здравствуйте, спасибо за Вашу заявку, до начала игры осталось совсем чуть-чуть.");
    }

    public java.util.List<String> applicationsQuestions() {
        java.util.List<String> questions = config.getStringList("applications.rooms.questions");
        return questions.isEmpty() ? java.util.List.of(
                "Чем вас зацепил наш сервер?",
                "Согласны ли вы с правилами проекта?") : questions;
    }

    public String applicationsShopNote() {
        return config.getString("applications.rooms.shop-note",
                "Если вы заждались получения проходки, то можете купить её на [нашем сайте](https://sunshinesmp.cdonate.ru/).");
    }

    /** Текст про трейлер (лайк + комментарий). Плейсхолдер {url}. Пусто, если ссылка не задана. */
    public String applicationsTrailerNote() {
        String url = config.getString("applications.rooms.trailer-url", "").trim();
        if (url.isEmpty()) {
            return "";
        }
        return config.getString("applications.rooms.trailer-note",
                "Для принятия заявки обязательно надо поставить лайк и написать комментарий от 3-х слов под этим видео ({url}), приложите скрин.")
                .replace("{url}", url);
    }

    /** Текст одобрения (отправляется вебхуком в канал заявки). Плейсхолдер {ip}. */
    public String applicationsApprovedText() {
        return config.getString("applications.rooms.approved",
                "## ✅ Ваша заявка одобрена!\nЖелаем вам приятной игры на проекте SunShine,\n**IP:** ```{ip}```");
    }

    /** Текст отклонения (вебхуком в канал заявки); причина модератора добавляется отдельной строкой. */
    public String applicationsDeclinedText() {
        return config.getString("applications.rooms.declined",
                "## ❌ Ваша заявка отклонена!\nВы можете подать заявку заново,\nне забывайте про правила заполнения.");
    }

    public String applicationsPanelTitle() {
        return config.getString("applications.panel-title", "Заявки на SunShine");
    }

    public String applicationsPanelDescription() {
        return config.getString("applications.panel-description", "Нажмите кнопку ниже, чтобы подать заявку на сервер.");
    }

    public String applicationsButtonLabel() {
        return config.getString("applications.button-label", "Подать заявку");
    }

    /**
     * Mirror linked players (uuid, Minecraft name, Discord id, last IP, total playtime) into a
     * MySQL table for external access. linked-accounts.yml stays the source of truth.
     */
    public boolean playerDbEnabled() {
        return config.getBoolean("player-db.enabled", false);
    }

    public String playerDbHost() {
        return config.getString("player-db.host", "127.0.0.1");
    }

    public int playerDbPort() {
        return config.getInt("player-db.port", 3306);
    }

    public String playerDbName() {
        return config.getString("player-db.name", "sunshine");
    }

    public String playerDbUser() {
        return config.getString("player-db.user", "sunshine");
    }

    public String playerDbPassword() {
        return config.getString("player-db.password", "");
    }

    public String playerDbTable() {
        return config.getString("player-db.table", "sunshine_players");
    }

    // --- Автобэкап MySQL (com.sunshine.plugin.backup) ---

    public boolean backupEnabled() {
        return config.getBoolean("backup.enabled", false);
    }

    public int backupIntervalHours() {
        return config.getInt("backup.interval-hours", 4);
    }

    /** Бэкапы старше этого количества часов удаляются (самый свежий - никогда). */
    public int backupKeepHours() {
        return config.getInt("backup.keep-hours", 48);
    }

    public String backupDirectory() {
        return config.getString("backup.directory", "backups");
    }

    public String backupDiscordChannelId() {
        return config.getString("backup.discord-channel-id", "");
    }

    /**
     * PlaceholderAPI placeholder resolved on join/quit to fill the playtime_seconds column.
     * Blank = don't track playtime. Default targets FlectonePulse's total online time.
     */
    public String playerDbPlaytimePlaceholder() {
        return config.getString("player-db.playtime-placeholder", "%flectonepulse_online_total%");
    }

    /** Включает Discord-команду /profile и игровую /sunshine profile. */
    public boolean profileEnabled() {
        return config.getBoolean("profile.enabled", false);
    }

    /**
     * Discord-роли, которым разрешено жать кнопки модерации под /profile (бан/мут/кик/варн и
     * list-шорткаты). Пусто = кнопки недоступны никому (fail-closed) - карточка профиля при этом
     * всё равно показывается, это не секретные данные. Поддерживается и старый одиночный ключ
     * profile.moderator-role-id для обратной совместимости.
     */
    public java.util.List<String> profileModeratorRoleIds() {
        java.util.List<String> ids = config.getStringList("profile.moderator-role-ids");
        if (!ids.isEmpty()) return ids;
        String legacy = config.getString("profile.moderator-role-id", "");
        return legacy.isBlank() ? java.util.List.of() : java.util.List.of(legacy);
    }

    public String profileBanCommand() {
        return config.getString("profile.commands.ban", "ban {player} {duration} {reason}");
    }

    public String profileMuteCommand() {
        return config.getString("profile.commands.mute", "mute {player} {duration} {reason}");
    }

    public String profileKickCommand() {
        return config.getString("profile.commands.kick", "kick {player} {reason}");
    }

    public String profileWarnCommand() {
        return config.getString("profile.commands.warn", "warn {player} {reason}");
    }

    /** Слать наказанному игроку личное сообщение в Discord (если привязан) о выданном бане/муте/кике/варне. */
    public boolean notifyPunishedPlayerEnabled() {
        return config.getBoolean("profile.notify-punished-player", true);
    }

    public boolean supportEnabled() {
        return config.getBoolean("support.enabled", false);
    }

    public String supportToken() {
        return config.getString("support.token", "");
    }

    public String ticketsCategoryId() {
        return config.getString("support.tickets.category-id", "");
    }

    public java.util.List<String> ticketsSupportRoleIds() {
        return config.getStringList("support.tickets.support-role-ids");
    }

    public String ticketsLogChannelId() {
        return config.getString("support.tickets.log-channel-id", "");
    }

    public String staffAppCategoryId() {
        String own = config.getString("support.staff-applications.category-id", "");
        return own.isBlank() ? applicationsRoomsCategoryId() : own;
    }

    public String staffAppRequiredRoleId() {
        return config.getString("support.staff-applications.required-role-id", "");
    }

    public java.util.List<String> staffAppReviewerRoleIds() {
        return config.getStringList("support.staff-applications.reviewer-role-ids");
    }

    // --- Общины/территории (com.sunshine.plugin.territory) - см. ТЗ, Приложение A ---

    public boolean territoryEnabled() {
        return config.getBoolean("territory.enabled", false);
    }

    /** Мир, в котором разрешено создавать территории (один на весь сервер - см. ТЗ раздел 0.1). */
    public String territoryWorld() {
        return config.getString("territory.world", "world");
    }

    /** Discord-роли с доступом к админ-панели/`/admin_terra` (ТЗ раздел 5/6). */
    public java.util.List<String> territoryAdminRoleIds() {
        return config.getStringList("territory.admin-role-ids");
    }

    public String territoryReviewChannelId() {
        return config.getString("territory.channels.review", "");
    }

    public String territoryListChannelId() {
        return config.getString("territory.channels.list", "");
    }

    public String territoryLogChannelId() {
        return config.getString("territory.channels.log", "");
    }

    public String territoryPanelChannelId() {
        return config.getString("territory.channels.panel", "");
    }

    public String territoryListBannerUrl() {
        return config.getString("territory.list-banner-url", "");
    }

    public int territoryMinSize() {
        return config.getInt("territory.size.min", 20);
    }

    public int territoryMaxSize() {
        return config.getInt("territory.size.max", 300);
    }

    public int territoryTagMinLength() {
        return config.getInt("territory.tag.min-length", 2);
    }

    public int territoryTagMaxLength() {
        return config.getInt("territory.tag.max-length", 6);
    }
}
