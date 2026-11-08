package com.sunshine.plugin.profile;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Следит за таблицей {@code fp_moderation} (FlectonePulse, после переноса на MySQL - см. GUIDE.md
 * раздел 10) и на каждую новую запись: (1) шлёт embed в {@code channels.moderation-log}, (2) если
 * {@code profile.notify-punished-player} включён и наказанный привязан к Discord - шлёт ему личное
 * сообщение о том, что ему выдали, с причиной/сроком/модератором. Читает базу напрямую, поэтому
 * ловит вообще все наказания - выданные через кнопки /profile, через {@code /sunshine punish},
 * набранные прямо в консоли сервера или игровой командой в обход SunShine.
 */
public final class ModerationLogPoller {

    private static final long POLL_PERIOD_TICKS = 20L * 10; // раз в 10 секунд
    private static final Set<String> LOGGED_TYPES = Set.of("BAN", "MUTE", "KICK", "WARN", "UNBAN", "UNMUTE", "UNWARN");
    private static final Set<String> PUNISH_TYPES = Set.of("BAN", "MUTE", "KICK", "WARN");
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SunShinePlugin plugin;
    private volatile long lastSeenId = -1;
    private BukkitTask task;

    public ModerationLogPoller(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (plugin.config().moderationLogChannelId().isBlank() && !plugin.config().notifyPunishedPlayerEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            lastSeenId = plugin.flectonePulseHistory().currentMaxModerationId();
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, POLL_PERIOD_TICKS, POLL_PERIOD_TICKS);
        });
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void poll() {
        if (!plugin.discordBot().isReady()) return;

        var entries = plugin.flectonePulseHistory().moderationSince(lastSeenId, 20);
        for (var entry : entries) {
            lastSeenId = entry.id();
            handle(entry);
        }
    }

    /**
     * Обрабатывает одну запись наказания: embed в {@code channels.moderation-log} и личное сообщение
     * наказанному. Вызывается и поллером таблицы FlectonePulse, и мостом LiteBans (см. {@link LiteBansBridge}).
     */
    public void handle(FlectonePulseHistory.ModerationLogEntry entry) {
        if (!LOGGED_TYPES.contains(entry.type())) return;

        String moderatorName = resolveModeratorName(entry);
        String channelId = plugin.config().moderationLogChannelId();
        if (!channelId.isBlank()) {
            logEntry(channelId, entry, moderatorName);
        }
        if (plugin.config().notifyPunishedPlayerEnabled() && PUNISH_TYPES.contains(entry.type())) {
            notifyPunishedPlayer(entry, moderatorName);
        }
    }

    private String resolveModeratorName(FlectonePulseHistory.ModerationLogEntry entry) {
        String moderatorName = entry.moderatorName();
        if (moderatorName == null || moderatorName.equalsIgnoreCase("console")) {
            String realModerator = ModerationExecutor.consumeRecentAttribution(entry.playerName(), entry.type());
            return realModerator != null ? realModerator : "Консоль";
        }
        return moderatorName;
    }

    private void logEntry(String channelId, FlectonePulseHistory.ModerationLogEntry entry, String moderatorName) {
        boolean revoke = entry.type().startsWith("UN");
        String icon = switch (entry.type()) {
            case "BAN", "UNBAN" -> "🔨";   // 🔨
            case "MUTE", "UNMUTE" -> "🔇"; // 🔇
            case "KICK" -> "👢";           // 👢
            case "WARN", "UNWARN" -> "⚠";       // ⚠
            default -> "•";
        };

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(icon + " " + actionLabel(entry.type()) + ": " + entry.playerName())
                .setColor(new Color(revoke ? 0x57F287 : 0xED4245))
                .setThumbnail(com.sunshine.plugin.discord.ChatWebhookRelay.avatarUrlForName(entry.playerName()))
                .addField("Модератор", moderatorName, true);

        if (entry.type().equals("BAN") || entry.type().equals("MUTE")) {
            embed.addField("До", formatExpiry(entry.expiryEpochMs()), true);
        }
        if (!revoke) {
            embed.addField("Причина", entry.reason() == null || entry.reason().isBlank() ? "не указана" : entry.reason(), false);
        }
        embed.setTimestamp(Instant.now());

        plugin.discordBot().sendEmbedToChannel(channelId, embed.build());
    }

    /**
     * Личное сообщение наказанному игроку в Discord - что выдали, срок, причина, кто выдал.
     * Discord-аккаунт берём сначала из обычной привязки (/link), а если её нет - из последней
     * заявки на сервер (там тоже указан Discord-аккаунт, с которого её подали) - так ЛС доходит
     * и до тех, кто ещё не привязывался вручную, но подавал заявку.
     */
    private void notifyPunishedPlayer(FlectonePulseHistory.ModerationLogEntry entry, String moderatorName) {
        String discordId = resolveDiscordId(entry.playerName());
        if (discordId == null || discordId.isBlank()) {
            plugin.getLogger().info("ЛС о наказании (" + entry.type() + ") не отправлено: у " + entry.playerName()
                    + " нет привязанного Discord (ни /link, ни заявки).");
            return;
        }

        String icon = switch (entry.type()) {
            case "BAN" -> "🔨";
            case "MUTE" -> "🔇";
            case "KICK" -> "👢";
            case "WARN" -> "⚠";
            default -> "•";
        };

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(icon + " Тебе выдан(о): " + actionLabel(entry.type()))
                .setColor(new Color(0xED4245))
                .addField("Сервер", "SunShine", true)
                .addField("Модератор", moderatorName, true);

        if (entry.type().equals("BAN") || entry.type().equals("MUTE")) {
            embed.addField("До", formatExpiry(entry.expiryEpochMs()), true);
        }
        embed.addField("Причина", entry.reason() == null || entry.reason().isBlank() ? "не указана" : entry.reason(), false);
        embed.setTimestamp(Instant.now());

        plugin.discordBot().sendDirectMessage(discordId, embed.build());
    }

    private String resolveDiscordId(String playerName) {
        java.util.UUID uuid = plugin.linkManager().uuidForName(playerName);
        if (uuid != null) {
            String linked = plugin.linkManager().discordIdFor(uuid);
            if (linked != null && !linked.isBlank()) return linked;
        }
        var application = plugin.applicationManager().findLatestByNickname(playerName);
        return application == null ? null : application.discordId();
    }

    private String formatExpiry(long expiryEpochMs) {
        return expiryEpochMs == -1 ? "навсегда"
                : EXPIRY_FORMAT.format(Instant.ofEpochMilli(expiryEpochMs).atZone(ZoneId.systemDefault()));
    }

    private String actionLabel(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "BAN" -> "Бан";
            case "UNBAN" -> "Разбан";
            case "MUTE" -> "Мут";
            case "UNMUTE" -> "Размут";
            case "KICK" -> "Кик";
            case "WARN" -> "Варн";
            case "UNWARN" -> "Снятие варна";
            default -> type;
        };
    }
}
