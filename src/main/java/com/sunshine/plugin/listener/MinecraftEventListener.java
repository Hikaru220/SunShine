package com.sunshine.plugin.listener;

import com.sunshine.plugin.SunShinePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftEventListener implements Listener {

    /** Не чаще одного сообщения о смерти на игрока за этот интервал - защита от спама при "death loop" (застрял и умирает по кругу). */
    private static final long DEATH_RELAY_COOLDOWN_MS = 5000L;
    /** Локаль для рендера ванильных сообщений о смерти (deathMessage() - Translatable-компонент). */
    private static final Locale DEATH_MESSAGE_LOCALE = Locale.forLanguageTag("ru");

    private final SunShinePlugin plugin;
    private final Map<UUID, Long> lastDeathRelayAt = new ConcurrentHashMap<>();

    public MinecraftEventListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.config().minecraftToDiscordEnabled() || !plugin.config().chatToDiscordEnabled()) return;

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (plugin.config().chatWebhookEnabled()) {
            // через webhook - от лица игрока (ник + голова скина), без обёртки chatToDiscordFormat,
            // т.к. форматирование username/avatar тут задаёт сам Discord
            plugin.chatWebhookRelay().sendPlayerMessage(
                    plugin.config().chatChannelId(),
                    event.getPlayer().getName(),
                    com.sunshine.plugin.discord.ChatWebhookRelay.avatarUrl(event.getPlayer()),
                    message);
        } else {
            String discordMessage = plugin.config().chatToDiscordFormat()
                    .replace("{player}", event.getPlayer().getName())
                    .replace("{message}", message);
            plugin.discordBot().sendToChannel(plugin.config().chatChannelId(), discordMessage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        grantJoinRoleIfAccepted(event.getPlayer().getName());
        syncNicknameOnJoin(event.getPlayer());
        captureNameOnly(event.getPlayer());

        if (!plugin.config().minecraftToDiscordEnabled() || !plugin.config().joinEnabled()) return;

        String discordMessage = plugin.config().joinFormat()
                .replace("{player}", event.getPlayer().getName());
        Player joined = event.getPlayer();
        boolean viaWebhook = plugin.config().joinWebhookEnabled();
        if (viaWebhook) {
            // SkinsRestorer выставляет скин пирату асинхронно, через секунду после входа: ждём, чтобы взять уже применённый скин
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> sendEventMessage(joined, discordMessage, true, COLOR_JOIN), 30L);
        } else {
            sendEventMessage(joined, discordMessage, false, COLOR_JOIN);
        }
    }

    /**
     * Renames a linked player's Discord server nickname to match the Minecraft nickname they
     * just joined with, so the two always stay in sync.
     */
    private void syncNicknameOnJoin(org.bukkit.entity.Player player) {
        if (!plugin.config().syncNicknameOnJoin()) return;

        String discordId = plugin.linkManager().discordIdFor(player.getUniqueId());
        if (discordId == null) return;

        plugin.discordBot().setNickname(discordId, plugin.formatDiscordNickname(player.getUniqueId(), player.getName()));
    }

    /** На входе фиксируем только ник. Не удалено - для линкованного игрока обновляет linked-accounts.yml + player-db. */
    private void captureNameOnly(Player player) {
        if (!plugin.linkManager().isLinked(player.getUniqueId())) return;
        plugin.linkManager().setName(player.getUniqueId(), player.getName());
    }

    /**
     * На выходе: ник + время онлайна (если PlaceholderAPI есть). Playtime сознательно НЕ пишем
     * на входе (см. captureNameOnly) - сразу после джоина плейсхолдер FlectonePulse может ещё не
     * успеть прогрузить накопленное время игрока и отдать 0/заниженное значение, которое тогда
     * затирало бы реальные часы. На выходе, после полной сессии, значение надёжнее - но даже так
     * не пишем, если оно меньше уже сохранённого (время должно только расти).
     */
    private void capturePlayerDataOnQuit(Player player) {
        if (!plugin.linkManager().isLinked(player.getUniqueId())) return;
        plugin.linkManager().setName(player.getUniqueId(), player.getName());

        if (plugin.playerDatabase() == null) return;
        Long playtime = resolvePlaytimeSeconds(player);
        if (playtime == null) return;

        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Long stored;
            try {
                var row = plugin.playerDatabase().get(uuid);
                stored = row == null ? null : row.playtimeSeconds();
            } catch (Exception e) {
                plugin.getLogger().warning("Playtime: не удалось прочитать текущее значение для " + uuid + ": " + e.getMessage());
                stored = null;
            }
            if (stored != null && playtime < stored) {
                plugin.getLogger().warning("Playtime для " + uuid + ": новое значение (" + playtime
                        + "с) меньше уже сохранённого (" + stored + "с) - не записываю, похоже на сбой плейсхолдера.");
                return;
            }
            plugin.linkManager().mirror(uuid, playtime);
        });
    }

    /** Resolves the configured playtime placeholder (e.g. FlectonePulse total online) to seconds. */
    private Long resolvePlaytimeSeconds(Player player) {
        String placeholder = plugin.config().playerDbPlaytimePlaceholder();
        if (placeholder == null || placeholder.isBlank()) return null;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return null;

        String raw;
        try {
            raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder).trim();
        } catch (Throwable t) {
            return null;
        }
        if (raw.isEmpty() || raw.startsWith("%")) return null; // unresolved
        try {
            // placeholder returns whole seconds; tolerate a stray decimal part
            int dot = raw.indexOf('.');
            return Long.parseLong(dot >= 0 ? raw.substring(0, dot) : raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gives the second ("joined the server") Discord role to a player's linked applicant
     * account the first time they actually log in after an accepted application.
     */
    private void grantJoinRoleIfAccepted(String playerName) {
        String roleId = plugin.config().applicationsJoinRoleId();
        if (roleId.isBlank()) return;

        var application = plugin.applicationManager().findAcceptedUngrantedByNickname(playerName);
        if (application == null) return;

        plugin.applicationManager().markJoinRoleGranted(application.discordId());
        plugin.discordBot().giveRole(application.discordId(), roleId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        capturePlayerDataOnQuit(event.getPlayer());

        if (!plugin.config().minecraftToDiscordEnabled() || !plugin.config().leaveEnabled()) return;

        String discordMessage = plugin.config().leaveFormat()
                .replace("{player}", event.getPlayer().getName());
        sendEventMessage(event.getPlayer(), discordMessage, plugin.config().leaveWebhookEnabled(), COLOR_LEAVE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().minecraftToDiscordEnabled() || !plugin.config().deathEnabled()) return;

        Component deathMessage = event.deathMessage();
        if (deathMessage == null) return;

        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastDeathRelayAt.get(uuid);
        if (last != null && now - last < DEATH_RELAY_COOLDOWN_MS) return; // "death loop" (застрял и умирает по кругу) - не спамим
        lastDeathRelayAt.put(uuid, now);

        // deathMessage() - переводимый (Translatable) компонент; без явного рендера в нужную
        // локаль сериализуется в английский оригинал ("... was impaled on a stalagmite").
        Component localized = GlobalTranslator.render(deathMessage, DEATH_MESSAGE_LOCALE);
        String plain = PlainTextComponentSerializer.plainText().serialize(localized);
        String discordMessage = plugin.config().deathFormat().replace("{message}", plain);
        sendEventMessage(player, discordMessage, plugin.config().deathWebhookEnabled(), COLOR_DEATH);
    }

    /**
     * Отправляет системное сообщение (вход/выход/смерть) в канал events. Через webhook уходит
     * карточкой (embed с цветной полосой) - ник+аватар игрока вместо бота; без webhook - обычным
     * текстовым сообщением бота, как раньше.
     */
    private void sendEventMessage(Player player, String message, boolean viaWebhook, int color) {
        String channelId = plugin.config().eventsChannelId();
        if (viaWebhook) {
            plugin.chatWebhookRelay().sendPlayerEmbed(channelId, player.getName(),
                    com.sunshine.plugin.discord.ChatWebhookRelay.avatarUrl(player), message, color);
        } else {
            plugin.discordBot().sendToChannel(channelId, message);
        }
    }

    private static final int COLOR_JOIN = 0x57F287;    // зелёный
    private static final int COLOR_LEAVE = 0x99AAB5;   // серый
    private static final int COLOR_DEATH = 0xED4245;   // красный
}
