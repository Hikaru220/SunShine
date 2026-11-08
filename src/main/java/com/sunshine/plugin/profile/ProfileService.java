package com.sunshine.plugin.profile;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.application.Application;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Собирает воедино всё, что SunShine знает об игроке, для команды /profile (и в Discord, и в
 * игре): ник, привязка Discord, время онлайна, текущая группа LuckPerms (+ когда истекает, если
 * временная), кто/когда принял его заявку на сервер, и (после переноса FlectonePulse на MySQL,
 * см. GUIDE.md раздел 10) реальный статус вайтлиста и история банов/мутов/варнов/киков из его
 * собственных таблиц ({@link FlectonePulseHistory}).
 */
public final class ProfileService {

    private final SunShinePlugin plugin;

    public ProfileService(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Находит UUID игрока по нику: сначала среди уже виденных SunShine ников (быстро, из памяти),
     * иначе — по кэшу сервера (без блокирующего запроса в Mojang). Возвращает null, если игрок
     * SunShine вообще не встречался.
     */
    public UUID resolveUuid(String nickname) {
        UUID fromLinkManager = plugin.linkManager().uuidForName(nickname);
        if (fromLinkManager != null) return fromLinkManager;

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(nickname);
        return cached == null ? null : cached.getUniqueId();
    }

    /**
     * Резолвит ник в последний известный IP (из player-db) и отдаёт его в callback асинхронно -
     * используется кнопкой/командой "Геолокация", которой не нужен весь профиль целиком.
     * Callback получает null, если игрок не найден или IP неизвестен.
     */
    public void resolveUuidAndIp(String nickname, java.util.function.Consumer<String> callback) {
        UUID uuid = resolveUuid(nickname);
        var playerDb = plugin.playerDatabase();
        if (uuid == null || playerDb == null) {
            callback.accept(null);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String ip = null;
            try {
                var row = playerDb.get(uuid);
                ip = row == null ? null : row.lastIp();
            } catch (Exception e) {
                plugin.getLogger().warning("Geolocate: не удалось прочитать player-db для " + uuid + ": " + e.getMessage());
            }
            String finalIp = ip;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(finalIp));
        });
    }

    /**
     * Собирает профиль. online/whitelisted читаются с основного потока (нужно для Bukkit API),
     * остальное (LuckPerms, MySQL) - асинхронно, поэтому в сумме метод не блокирует вызывающий поток.
     */
    public CompletableFuture<Profile> load(String nickname) {
        UUID uuid = resolveUuid(nickname);
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Profile> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean online = Bukkit.getPlayer(uuid) != null;
            // getOfflinePlayer(UUID) (не строковый) ничего не качает из сети - просто читает/создаёт локальную запись
            boolean vanillaWhitelisted = Bukkit.getOfflinePlayer(uuid).isWhitelisted();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    future.complete(buildProfile(uuid, nickname, online, vanillaWhitelisted));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        });
        return future;
    }

    private Profile buildProfile(UUID uuid, String requestedNickname, boolean online, boolean vanillaWhitelisted) {
        String resolvedNickname = plugin.linkManager().nameFor(uuid);
        String nickname = resolvedNickname != null ? resolvedNickname : requestedNickname;

        String discordId = plugin.linkManager().discordIdFor(uuid);
        PlayerDbInfo dbInfo = readPlayerDbInfo(uuid);

        GroupInfo groupInfo = readGroupInfo(uuid);

        Application application = plugin.applicationManager().findLatestByNickname(nickname);

        // Настоящий статус вайтлиста живёт у FlectonePulse (applications.whitelist-command -
        // это его команда, не ванильная), а не в ванильном Bukkit-вайтлисте. Если БД FlectonePulse
        // недоступна (ещё не перенесена на MySQL, см. GUIDE.md раздел 10) - используем ванильный
        // статус как запасной вариант, чтобы поле вообще не пустовало.
        Boolean flectoneWhitelisted = plugin.flectonePulseHistory().isWhitelisted(uuid);
        boolean whitelisted = flectoneWhitelisted != null ? flectoneWhitelisted : vanillaWhitelisted;

        List<FlectonePulseHistory.PunishmentRecord> punishments = plugin.flectonePulseHistory().recentPunishments(uuid, 5);

        // Реальное суммарное время берём напрямую из fp_time (FlectonePulse) - оно не зависит от
        // того, успевал ли player-db поймать его на выходе с сервера, и уже верное для всех, кто
        // был перенесён при миграции. player-db.playtime_seconds - запасной вариант.
        Long playtime = plugin.flectonePulseHistory().totalPlaytimeSeconds(uuid);
        if (playtime == null) playtime = dbInfo == null ? null : dbInfo.playtimeSeconds;

        return new Profile(
                nickname,
                uuid,
                discordId,
                online,
                whitelisted,
                playtime,
                dbInfo == null ? null : dbInfo.lastIp,
                groupInfo == null ? null : groupInfo.groupName,
                groupInfo == null ? null : groupInfo.expiryEpochSeconds,
                application == null ? null : application.status().name(),
                application == null ? null : application.decidedBy(),
                application == null ? null : application.submittedAt(),
                punishments
        );
    }

    /** Последнее сохранённое время онлайна и IP из player-db (если он включён). */
    private PlayerDbInfo readPlayerDbInfo(UUID uuid) {
        var playerDb = plugin.playerDatabase();
        if (playerDb == null) return null;
        try {
            var row = playerDb.get(uuid);
            return row == null ? null : new PlayerDbInfo(row.playtimeSeconds(), row.lastIp());
        } catch (Exception e) {
            plugin.getLogger().warning("/profile: не удалось прочитать player-db для " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private record PlayerDbInfo(Long playtimeSeconds, String lastIp) {
    }

    private GroupInfo readGroupInfo(UUID uuid) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;

        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user == null) {
                // блокирующий, но мы и так уже на асинхронном потоке (см. load())
                user = luckPerms.getUserManager().loadUser(uuid).join();
            }
            if (user == null) return null;

            String primaryGroup = user.getPrimaryGroup();

            Optional<InheritanceNode> matchingNode = user.getNodes(NodeType.INHERITANCE).stream()
                    .filter(node -> node.getGroupName().equalsIgnoreCase(primaryGroup))
                    .findFirst();

            Long expiry = matchingNode
                    .filter(InheritanceNode::hasExpiry)
                    .map(node -> node.getExpiry().getEpochSecond())
                    .orElse(null);

            return new GroupInfo(primaryGroup, expiry);
        } catch (Exception e) {
            plugin.getLogger().warning("/profile: ошибка при обращении к LuckPerms для " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private record GroupInfo(String groupName, Long expiryEpochSeconds) {
    }

    /**
     * @param nickname               последний известный ник игрока
     * @param uuid                   UUID игрока
     * @param discordId              привязанный Discord ID, null если не привязан
     * @param online                 сейчас на сервере или нет
     * @param whitelisted            есть ли в локальном вайтлисте (ванильный Bukkit API, всегда точно)
     * @param playtimeSeconds        суммарное время онлайна в секундах, null если неизвестно
     * @param lastIp                 последний известный IP (из player-db), null если неизвестен - нужен для кнопки Geolocate
     * @param groupName              текущая (primary) группа LuckPerms, null если LuckPerms недоступен
     * @param groupExpiryEpochSeconds когда истекает текущая группа (unix-время), null = не временная/неизвестно
     * @param applicationStatus      статус последней заявки этого ника (PENDING/ACCEPTED/DECLINED), null = не подавал
     * @param applicationDecidedBy   кто принял/отклонил заявку, null = ещё не рассмотрена или заявки нет
     * @param applicationSubmittedAt когда подана заявка (epoch millis), null = заявки нет
     * @param punishments            последние баны/муты/варны/кики из БД FlectonePulse (см. {@link FlectonePulseHistory}),
     *                               пустой список = ничего не найдено (или FlectonePulse ещё не на MySQL)
     */
    public record Profile(String nickname, UUID uuid, String discordId, boolean online, boolean whitelisted,
                           Long playtimeSeconds, String lastIp,
                           String groupName, Long groupExpiryEpochSeconds,
                           String applicationStatus, String applicationDecidedBy, Long applicationSubmittedAt,
                           List<FlectonePulseHistory.PunishmentRecord> punishments) {
    }
}
