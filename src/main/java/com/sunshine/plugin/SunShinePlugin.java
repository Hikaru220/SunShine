package com.sunshine.plugin;

import com.sunshine.plugin.application.ApplicationListener;
import com.sunshine.plugin.application.ApplicationManager;
import com.sunshine.plugin.command.SunShineCommand;
import com.sunshine.plugin.config.SunShineConfig;
import com.sunshine.plugin.console.ConsoleLogRelay;
import com.sunshine.plugin.console.DiscordConsoleListener;
import com.sunshine.plugin.discord.ChatWebhookRelay;
import com.sunshine.plugin.discord.DiscordBot;
import com.sunshine.plugin.discord.DiscordChatListener;
import com.sunshine.plugin.discord.DiscordLinkListener;
import com.sunshine.plugin.discord.LoginConfirmationInteractionListener;
import com.sunshine.plugin.discord.ProfileCommandListener;
import com.sunshine.plugin.groupsync.GroupSyncManager;
import com.sunshine.plugin.link.LinkManager;
import com.sunshine.plugin.link.LoginConfirmationManager;
import com.sunshine.plugin.listener.LoginGateListener;
import com.sunshine.plugin.listener.MinecraftEventListener;
import com.sunshine.plugin.profile.FlectonePulseHistory;
import com.sunshine.plugin.profile.GeoLocationService;
import com.sunshine.plugin.storage.PlayerDatabase;
import com.sunshine.plugin.support.GuildInfra;
import com.sunshine.plugin.support.StaffApplicationListener;
import com.sunshine.plugin.support.SupportBot;
import com.sunshine.plugin.support.TicketListener;
import com.sunshine.plugin.territory.CommunityManager;
import com.sunshine.plugin.territory.TerritoryBlueMapIntegration;
import com.sunshine.plugin.territory.TerritoryDatabase;
import com.sunshine.plugin.territory.TerritoryListChannelUpdater;
import com.sunshine.plugin.territory.TerritoryTitlesIntegration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class SunShinePlugin extends JavaPlugin {

    private static SunShinePlugin instance;

    private SunShineConfig config;
    private DiscordBot discordBot;
    private LinkManager linkManager;
    private LoginConfirmationManager loginConfirmationManager;
    private ApplicationManager applicationManager;
    private volatile PlayerDatabase playerDatabase;
    private ChatWebhookRelay chatWebhookRelay;
    private ConsoleLogRelay consoleLogRelay;
    private GroupSyncManager groupSyncManager;
    private ApplicationListener applicationListener;
    private FlectonePulseHistory flectonePulseHistory;
    private GeoLocationService geoLocationService;
    private SupportBot supportBot;
    private com.sunshine.plugin.profile.ModerationLogPoller moderationLogPoller;
    private com.sunshine.plugin.profile.LiteBansBridge liteBansBridge;
    private com.sunshine.plugin.discord.GatewayNoiseFilter gatewayNoiseFilter;
    private TerritoryTitlesIntegration territoryTitlesIntegration;
    private volatile TerritoryDatabase territoryDatabase;
    private volatile CommunityManager communityManager;
    private volatile TerritoryListChannelUpdater territoryListChannelUpdater;
    private volatile TerritoryBlueMapIntegration territoryBlueMapIntegration;
    private com.sunshine.plugin.backup.DatabaseBackupService databaseBackupService;
    private com.sunshine.plugin.court.CourtBot courtBot;
    private com.sunshine.plugin.court.CourtService courtService;

    public static SunShinePlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadConfig();
        config = new SunShineConfig(getConfig());

        linkManager = new LinkManager(this);
        loginConfirmationManager = new LoginConfirmationManager();
        applicationManager = new ApplicationManager(this);
        if (config.hideGatewayCertNoise()) {
            gatewayNoiseFilter = com.sunshine.plugin.discord.GatewayNoiseFilter.install(this);
        }
        discordBot = new DiscordBot(this);
        chatWebhookRelay = new ChatWebhookRelay(this);
        flectonePulseHistory = new FlectonePulseHistory(this);
        geoLocationService = new GeoLocationService(this);
        territoryTitlesIntegration = new TerritoryTitlesIntegration(this);

        setupPlayerDatabase();
        setupTerritory();

        databaseBackupService = new com.sunshine.plugin.backup.DatabaseBackupService(this);
        databaseBackupService.start();

        setupCourt();

        getServer().getPluginManager().registerEvents(new MinecraftEventListener(this), this);
        getServer().getPluginManager().registerEvents(new LoginGateListener(this), this);

        groupSyncManager = new GroupSyncManager(this);
        getServer().getPluginManager().registerEvents(groupSyncManager, this);

        SunShineCommand command = new SunShineCommand(this);
        getCommand("sunshine").setExecutor(command);
        getCommand("sunshine").setTabCompleter(command);

        com.sunshine.plugin.command.ProfileCommand profileCommand = new com.sunshine.plugin.command.ProfileCommand(this, command);
        getCommand("profile").setExecutor(profileCommand);
        getCommand("profile").setTabCompleter(profileCommand);

        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                discordBot.start(() -> {
                    getLogger().info("Connected to Discord as " + discordBot.jda().getSelfUser().getAsTag());
                    Bukkit.getScheduler().runTask(this, () -> {
                        startStatusUpdater();
                        if (config.consoleEnabled()) {
                            consoleLogRelay = new ConsoleLogRelay(this);
                            consoleLogRelay.start(config.consoleChannelId());
                        }
                        if (config.groupSyncEnabled()) {
                            // стартовая пересинхронизация тех, кто уже онлайн на момент коннекта к Discord
                            Bukkit.getScheduler().runTaskAsynchronously(this, groupSyncManager::syncOnline);
                            Bukkit.getScheduler().runTaskTimerAsynchronously(this, groupSyncManager::syncOnline, 6000L, 6000L);
                        }
                        if (!config.moderationLogChannelId().isBlank() || config.notifyPunishedPlayerEnabled()) {
                            moderationLogPoller = new com.sunshine.plugin.profile.ModerationLogPoller(this);
                            moderationLogPoller.start();
                            // наказания через LiteBans в fp_moderation не попадают - слушаем его события напрямую
                            if (getServer().getPluginManager().getPlugin("LiteBans") != null) {
                                try {
                                    liteBansBridge = new com.sunshine.plugin.profile.LiteBansBridge(this, moderationLogPoller);
                                    liteBansBridge.register();
                                } catch (Throwable t) {
                                    getLogger().warning("LiteBans: не удалось подключить мост наказаний: " + t);
                                }
                            }
                        }
                        if (communityManager != null) {
                            // Discord только что подключился - публикуем/обновляем #список-общин
                            // сейчас же (до этого refresh() внутри CommunityManager молча ничего не
                            // делал, т.к. discordBot.isReady() был false).
                            Bukkit.getScheduler().runTaskAsynchronously(this, communityManager::refreshCache);
                        }
                    });
                },
                        new DiscordChatListener(this),
                        new DiscordLinkListener(this),
                        new LoginConfirmationInteractionListener(this),
                        (applicationListener = new ApplicationListener(this)),
                        new DiscordConsoleListener(this),
                        new ProfileCommandListener(this),
                        new com.sunshine.plugin.discord.TerritoryPanelListener(this)
                ));

        supportBot = new SupportBot(this);
        if (config.supportEnabled()) {
            GuildInfra guildInfra = new GuildInfra();
            Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                    supportBot.start(
                            new TicketListener(this, guildInfra),
                            new StaffApplicationListener(this, guildInfra)
                    ));
        }
    }

    /** Суд - отдельный Discord-бот со своим токеном (как support-бот), включается через court.enabled. */
    private void setupCourt() {
        com.sunshine.plugin.court.CourtSettings settings = new com.sunshine.plugin.court.CourtSettings(this);
        if (!settings.enabled()) {
            return;
        }
        courtBot = new com.sunshine.plugin.court.CourtBot(this);
        courtService = new com.sunshine.plugin.court.CourtService(this, settings, courtBot);
        getServer().getPluginManager().registerEvents(courtService, this);
        courtService.start();
        if (settings.tagsEnabled() && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            boolean registered = new com.sunshine.plugin.court.CourtPlaceholders(settings, courtService).register();
            getLogger().info("Court: PlaceholderAPI-расширение 'sunshinecourt' " + (registered
                    ? "зарегистрировано - %sunshinecourt_suffix%/_prefix%/_mark%/_role% доступны."
                    : "НЕ зарегистрировалось."));
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                courtBot.start(new com.sunshine.plugin.court.CourtListener(courtService)));
    }

    @Override
    public void onDisable() {
        if (courtService != null) {
            courtService.stop();
        }
        if (courtBot != null) {
            courtBot.stop();
        }
        if (databaseBackupService != null) {
            databaseBackupService.stop();
        }
        if (consoleLogRelay != null) {
            consoleLogRelay.stop();
        }
        if (liteBansBridge != null) {
            liteBansBridge.unregister();
            liteBansBridge = null;
        }
        if (gatewayNoiseFilter != null) {
            gatewayNoiseFilter.disable();
        }
        if (moderationLogPoller != null) {
            moderationLogPoller.stop();
        }
        if (discordBot != null) {
            discordBot.stop();
        }
        if (supportBot != null) {
            supportBot.stop();
        }
        if (playerDatabase != null) {
            playerDatabase.close();
        }
        if (flectonePulseHistory != null) {
            flectonePulseHistory.close();
        }
        if (territoryDatabase != null) {
            territoryDatabase.close();
        }
    }

    /** Общины/территории (ТЗ) - включается отдельно, не тянется за player-db.enabled, т.к. это не связанные фичи. */
    private void setupTerritory() {
        if (!config.territoryEnabled()) {
            return;
        }
        TerritoryDatabase db = new TerritoryDatabase(this);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                db.connect();
                this.territoryDatabase = db;
                this.territoryListChannelUpdater = new TerritoryListChannelUpdater(this);
                // Создаём мост к BlueMap ТОЛЬКО если он реально установлен - иначе даже просто
                // создание TerritoryBlueMapIntegration уронит сервер с NoClassDefFoundError на
                // первом обращении к его классам (BlueMap - provided-зависимость, в рантайме её
                // классы существуют только если сам плагин BlueMap стоит на сервере).
                if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
                    this.territoryBlueMapIntegration = new TerritoryBlueMapIntegration(this);
                } else {
                    getLogger().warning("Territory: плагин BlueMap не найден (Bukkit.getPluginManager().getPlugin(\"BlueMap\") == null) - "
                            + "территории не будут рисоваться на карте. Если BlueMap реально установлен - проверь точное имя "
                            + "плагина в его plugin.yml/через /plugins и добавь SunShine в softdepend BlueMap (или наоборот).");
                }
                CommunityManager manager = new CommunityManager(this, db);
                manager.refreshCache();
                // Разовая миграция имени зоны для уже существующих общин (см. javadoc метода) -
                // после того, как выше подключился BlueMap/TerritoryTitles мост.
                manager.resyncAllZonesOnStartup();
                this.communityManager = manager;
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    // Регистрация PlaceholderExpansion трогает реестр PlaceholderAPI - на всякий
                    // случай делаем это на основном потоке, а не из этого асинхронного коллбэка.
                    Bukkit.getScheduler().runTask(this, () -> {
                        boolean registered = new com.sunshine.plugin.territory.TerritoryPlaceholders(this).register();
                        getLogger().info("Territory: PlaceholderAPI-расширение 'sunshineterritory' "
                                + (registered ? "зарегистрировано - %sunshineterritory_prefix%/_tag%/_name% доступны."
                                        : "НЕ зарегистрировалось (register() вернул false) - плейсхолдеры работать не будут."));
                    });
                } else {
                    getLogger().warning("Territory: PlaceholderAPI не найден - %sunshineterritory_prefix% и другие плейсхолдеры не будут работать.");
                }
                getLogger().info("Territory: модуль общин включён.");
            } catch (Exception e) {
                getLogger().warning("Не удалось подключиться к Territory-БД, общины отключены: " + e.getMessage());
            }
        });
    }

    private void setupPlayerDatabase() {
        if (!config.playerDbEnabled()) {
            return;
        }
        PlayerDatabase db = new PlayerDatabase(this,
                config.playerDbHost(), config.playerDbPort(), config.playerDbName(),
                config.playerDbUser(), config.playerDbPassword(), config.playerDbTable());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                db.connect();
                this.playerDatabase = db;
                syncPlayerDatabase(getServer().getConsoleSender());
            } catch (Exception e) {
                getLogger().warning("Не удалось подключиться к БД игроков, зеркалирование привязок отключено: "
                        + e.getMessage());
            }
        });
    }

    /** Pushes every linked player from linked-accounts.yml into the MySQL mirror. Idempotent (upsert). */
    public void syncPlayerDatabase(org.bukkit.command.CommandSender feedback) {
        if (playerDatabase == null) {
            feedback.sendMessage("БД игроков не подключена — синхронизация пропущена.");
            return;
        }
        var snapshot = linkManager.linkedSnapshot();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int ok = 0;
            for (LinkManager.LinkedPlayer p : snapshot.values()) {
                try {
                    // Подтягиваем реальное время онлайна из fp_time (FlectonePulse), если доступно -
                    // так db-sync чинит старые записи с playtime_seconds = NULL/0, не дожидаясь
                    // повторного входа/выхода игрока на сервер.
                    Long playtime = flectonePulseHistory != null ? flectonePulseHistory.totalPlaytimeSeconds(p.uuid()) : null;
                    playerDatabase.upsert(p.uuid(), p.name(), p.discordId(), p.lastIp(), playtime);
                    ok++;
                } catch (Exception e) {
                    getLogger().warning("БД игроков: не удалось залить " + p.uuid() + ": " + e.getMessage());
                }
            }
            getLogger().info("БД игроков: зеркалировано привязок — " + ok + " из " + snapshot.size() + ".");
            feedback.sendMessage("БД игроков: залито " + ok + " из " + snapshot.size() + " привязок.");
        });
    }

    private void startStatusUpdater() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!discordBot.isReady()) return;
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            String status = config.botStatus()
                    .replace("{online}", String.valueOf(online))
                    .replace("{max}", String.valueOf(max));
            discordBot.updateStatus(status);
        }, 0L, 20L * 60L);
    }

    public void reload() {
        reloadConfig();
        config = new SunShineConfig(getConfig());
    }

    public SunShineConfig config() {
        return config;
    }

    public com.sunshine.plugin.backup.DatabaseBackupService databaseBackupService() {
        return databaseBackupService;
    }

    public DiscordBot discordBot() {
        return discordBot;
    }

    public LinkManager linkManager() {
        return linkManager;
    }

    public LoginConfirmationManager loginConfirmationManager() {
        return loginConfirmationManager;
    }

    public ApplicationManager applicationManager() {
        return applicationManager;
    }

    public PlayerDatabase playerDatabase() {
        return playerDatabase;
    }

    public ChatWebhookRelay chatWebhookRelay() {
        return chatWebhookRelay;
    }

    public ApplicationListener applicationListener() {
        return applicationListener;
    }

    public GroupSyncManager groupSyncManager() {
        return groupSyncManager;
    }

    public FlectonePulseHistory flectonePulseHistory() {
        return flectonePulseHistory;
    }

    public GeoLocationService geoLocationService() {
        return geoLocationService;
    }

    public SupportBot supportBot() {
        return supportBot;
    }

    public TerritoryTitlesIntegration territoryTitlesIntegration() {
        return territoryTitlesIntegration;
    }

    public TerritoryDatabase territoryDatabase() {
        return territoryDatabase;
    }

    /** null, если territory.enabled=false или БД ещё не подключилась (асинхронный старт, см. setupTerritory()). */
    public CommunityManager communityManager() {
        return communityManager;
    }

    public TerritoryListChannelUpdater territoryListChannelUpdater() {
        return territoryListChannelUpdater;
    }

    /** null, если BlueMap не установлен - см. комментарий в setupTerritory(). */
    public TerritoryBlueMapIntegration territoryBlueMapIntegration() {
        return territoryBlueMapIntegration;
    }

    /**
     * Итоговый Discord-ник игрока с учётом клан-тега общины (ТЗ раздел 3.5) - "ТЕГ | ник", если
     * игрок состоит в общине, иначе просто ник. Общий метод для {@code syncNicknameOnJoin} (вход на
     * сервер) и {@link CommunityManager} (мгновенный пересчёт при вступлении/исключении/удалении
     * общины - без этого тег появлялся/пропадал бы только после переподключения к серверу).
     */
    public String formatDiscordNickname(java.util.UUID uuid, String minecraftName) {
        CommunityManager manager = communityManager;
        var community = manager == null ? null : manager.findCommunityForPlayerCached(uuid);
        return community == null ? minecraftName : community.tag() + " | " + minecraftName;
    }
}
