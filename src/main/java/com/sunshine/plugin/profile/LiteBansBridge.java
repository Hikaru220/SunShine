package com.sunshine.plugin.profile;

import com.sunshine.plugin.SunShinePlugin;
import litebans.api.Entry;
import litebans.api.Events;
import org.bukkit.Bukkit;

import java.util.Locale;
import java.util.UUID;

/**
 * Мост LiteBans -> Discord. Если наказания выдаёт LiteBans, таблица fp_moderation у FlectonePulse остаётся
 * пустой (Flectone только оформляет сообщения), и поллер {@link ModerationLogPoller} ничего не видит.
 * Здесь подписываемся на события самого LiteBans и отдаём каждое наказание в тот же код, что пишет в
 * {@code channels.moderation-log} и шлёт ЛС наказанному.
 *
 * Класс трогает API LiteBans, поэтому создавать его можно только когда плагин LiteBans загружен.
 */
public final class LiteBansBridge {

    private final SunShinePlugin plugin;
    private final ModerationLogPoller sink;
    private litebans.api.Events$Listener listener;

    public LiteBansBridge(SunShinePlugin plugin, ModerationLogPoller sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    public void register() {
        listener = new litebans.api.Events$Listener() {
            @Override
            public void entryAdded(Entry entry) {
                forward(entry, false);
            }

            @Override
            public void entryRemoved(Entry entry) {
                forward(entry, true);
            }
        };
        Events.get().register(listener);
        plugin.getLogger().info("LiteBans: наказания (бан/мут/варн/кик и снятия) будут отправляться в Discord.");
    }

    public void unregister() {
        if (listener != null) {
            try {
                Events.get().unregister(listener);
            } catch (Throwable ignored) {
                // LiteBans уже выгружен
            }
            listener = null;
        }
    }

    private void forward(Entry entry, boolean removed) {
        try {
            var converted = convert(entry, removed, this::playerName);
            if (converted != null) {
                sink.handle(converted);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("LiteBans: не удалось обработать наказание: " + t);
        }
    }

    /** Запись LiteBans -> запись для Discord-лога; null, если это не наказание (например, снятие кика). */
    static FlectonePulseHistory.ModerationLogEntry convert(Entry entry, boolean removed,
                                                           java.util.function.Function<Entry, String> names) {
        String type = entry.getType() == null ? "" : entry.getType().toLowerCase(Locale.ROOT);
        String mapped = switch (type) {
            case "ban" -> removed ? "UNBAN" : "BAN";
            case "mute" -> removed ? "UNMUTE" : "MUTE";
            case "warn", "warning" -> removed ? "UNWARN" : "WARN";
            case "kick" -> removed ? null : "KICK";
            default -> null;
        };
        if (mapped == null) return null;

        String moderator = removed ? entry.getRemovedByName() : entry.getExecutorName();
        long expiry = entry.isPermanent() ? -1 : entry.getDateEnd();
        String reason = removed ? entry.getRemovalReason() : entry.getReason();
        return new FlectonePulseHistory.ModerationLogEntry(entry.getId(), mapped, expiry, reason, names.apply(entry), moderator);
    }

    /** LiteBans отдаёт UUID (для бана по IP - служебное значение); ник берём из кэша сервера. */
    private String playerName(Entry entry) {
        String uuid = entry.getUuid();
        if (uuid != null) {
            try {
                String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
                if (name != null && !name.isBlank()) return name;
            } catch (IllegalArgumentException ignored) {
                // не UUID (например "#offline#" у бана по IP)
            }
        }
        if (entry.isIpban() && entry.getIp() != null) return "IP " + entry.getIp();
        return uuid == null ? "неизвестно" : uuid;
    }
}
