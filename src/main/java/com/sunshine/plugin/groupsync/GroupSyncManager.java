package com.sunshine.plugin.groupsync;

import com.sunshine.plugin.SunShinePlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Однонаправленная синхронизация LuckPerms → Discord ролей (по образцу DiscordSRV group sync).
 * <p>
 * Когда у привязанного игрока меняется набор групп LuckPerms, боту нужно выдать/снять
 * соответствующие Discord-роли согласно маппингу {@code group-sync.mappings} из конфига.
 * Направление СТРОГО одностороннее: LuckPerms → Discord. Ручная выдача/снятие роли в Discord
 * ничего не меняет в LuckPerms - подписки на события изменения ролей Discord здесь нет и не будет.
 * <p>
 * Поскольку прямой подписки на события LuckPerms нет (чтобы не тянуть лишнюю интеграцию),
 * триггером служит вход игрока на сервер ({@link PlayerJoinEvent}) - самый простой и надёжный
 * момент для пересинхронизации, плюс периодический/стартовый проход по онлайну через
 * {@link #syncOnline()} (вызывается оркестратором из {@code SunShinePlugin}).
 */
public class GroupSyncManager implements Listener {

    private final SunShinePlugin plugin;
    private volatile boolean warnedMissingLuckPerms = false;

    public GroupSyncManager(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Синхронизирует Discord-роли одного игрока с его текущими группами LuckPerms.
     * Ничего не делает, если: group-sync выключен в конфиге, игрок не привязан к Discord,
     * LuckPerms не установлен на сервере, или маппинг групп→ролей пуст.
     */
    public void syncPlayer(UUID uuid) {
        if (!plugin.config().groupSyncEnabled()) return;

        String discordId = plugin.linkManager().discordIdFor(uuid);
        if (discordId == null) return; // игрок не привязан - синхронизировать нечего

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            warnMissingLuckPermsOnce();
            return;
        }

        Map<String, String> mappings = plugin.config().groupSyncMappings();
        if (mappings == null || mappings.isEmpty()) return;

        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user != null) {
                applyGroupsToDiscord(discordId, collectGroupNames(user), mappings);
            } else {
                // Пользователь ещё не загружен в кэш LuckPerms (например, синк сразу на джоине) -
                // подгружаем асинхронно и применяем маппинг, когда данные будут готовы.
                luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(loadedUser -> {
                    if (loadedUser == null) return;
                    try {
                        applyGroupsToDiscord(discordId, collectGroupNames(loadedUser), mappings);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка синхронизации групп LuckPerms для " + uuid + ": " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при обращении к LuckPerms API для " + uuid + ": " + e.getMessage());
        }
    }

    /** Прогоняет {@link #syncPlayer(UUID)} по всем игрокам, которые сейчас онлайн. */
    public void syncOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayer(player.getUniqueId());
        }
    }

    /** Триггер пересинхронизации - вход игрока на сервер. Небольшая задержка даёт LuckPerms время прогрузить юзера. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().groupSyncEnabled()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> syncPlayer(uuid), 20L);
    }

    /** Собирает имена всех унаследованных групп игрока в нижнем регистре (для сравнения с конфигом). */
    private Set<String> collectGroupNames(User user) {
        Set<String> names = new HashSet<>();
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            names.add(group.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Для каждой пары "группа LuckPerms -> Discord-роль" из маппинга: если у игрока сейчас
     * есть эта группа - роль выдаётся, если нет - роль снимается. Discord-роли, не упомянутые
     * в маппинге, не трогаются вообще.
     */
    private void applyGroupsToDiscord(String discordId, Set<String> groupsLower, Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String group = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String roleId = entry.getValue();
            if (roleId == null || roleId.isBlank()) continue;

            if (groupsLower.contains(group)) {
                plugin.discordBot().giveRole(discordId, roleId);
            } else {
                plugin.discordBot().removeRole(discordId, roleId);
            }
        }
    }

    private void warnMissingLuckPermsOnce() {
        if (warnedMissingLuckPerms) return;
        warnedMissingLuckPerms = true;
        plugin.getLogger().warning("LuckPerms не найден на сервере - синхронизация групп LuckPerms -> Discord отключена.");
    }
}
