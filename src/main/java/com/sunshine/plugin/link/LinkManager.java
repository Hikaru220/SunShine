package com.sunshine.plugin.link;

import com.sunshine.plugin.SunShinePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LinkManager {

    private final SunShinePlugin plugin;
    private final File storageFile;

    // uuid -> discord user id
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    // uuid -> last IP that successfully completed a login confirmation
    private final Map<UUID, String> lastConfirmedIp = new ConcurrentHashMap<>();
    // uuid -> last known Minecraft nickname
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    // pending code -> pending link request
    private final Map<String, PendingLink> pendingCodes = new ConcurrentHashMap<>();

    public LinkManager(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "linked-accounts.yml");
        load();
    }

    private void load() {
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        for (String key : yaml.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue; // corrupt entry, skip
            }

            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section != null) {
                // current format: <uuid>: { discordId: ..., lastIp: ... }
                String discordId = section.getString("discordId");
                if (discordId != null) linkedAccounts.put(uuid, discordId);
                String ip = section.getString("lastIp");
                if (ip != null) lastConfirmedIp.put(uuid, ip);
                String name = section.getString("name");
                if (name != null) names.put(uuid, name);
            } else {
                // legacy format: <uuid>: <discordId>
                String discordId = yaml.getString(key);
                if (discordId != null) linkedAccounts.put(uuid, discordId);
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            String path = entry.getKey().toString();
            yaml.set(path + ".discordId", entry.getValue());
            String ip = lastConfirmedIp.get(entry.getKey());
            if (ip != null) yaml.set(path + ".lastIp", ip);
            String name = names.get(entry.getKey());
            if (name != null) yaml.set(path + ".name", name);
        }
        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save linked-accounts.yml: " + e.getMessage());
        }
    }

    public boolean isLinked(UUID uuid) {
        return linkedAccounts.containsKey(uuid);
    }

    public String discordIdFor(UUID uuid) {
        return linkedAccounts.get(uuid);
    }

    public String nameFor(UUID uuid) {
        return names.get(uuid);
    }

    /**
     * Finds a linked player's UUID by their last known Minecraft nickname (case-insensitive).
     * Only matches names SunShine has actually seen (join/quit since the name field was added) -
     * falls back to null if the player never logged in after that.
     */
    public UUID uuidForName(String name) {
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        }
        return null;
    }

    /** Remembers the player's current Minecraft nickname (persisted in yml, mirrored to MySQL). */
    public void setName(UUID uuid, String name) {
        if (name == null || name.equals(names.get(uuid))) return;
        names.put(uuid, name);
        save();
        mirror(uuid, null);
    }

    /**
     * Immutable snapshot of every linked player for the startup DB backfill. Для привязок, у
     * которых SunShine ещё не запомнил ник (старые записи с версии до того, как появился name в
     * linked-accounts.yml), подхватывает его из локального кэша Bukkit (без сетевого запроса) и
     * заодно сохраняет - иначе такие строки так и оставались бы в player-db без ника навсегда.
     */
    public Map<UUID, LinkedPlayer> linkedSnapshot() {
        Map<UUID, LinkedPlayer> out = new HashMap<>();
        boolean changed = false;
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            UUID uuid = entry.getKey();
            String name = names.get(uuid);
            if (name == null) {
                OfflinePlayer cached = Bukkit.getOfflinePlayer(uuid); // локальный кэш, без запроса в Mojang
                name = cached.getName();
                if (name != null) {
                    names.put(uuid, name);
                    changed = true;
                }
            }
            out.put(uuid, new LinkedPlayer(uuid, name, entry.getValue(), lastConfirmedIp.get(uuid)));
        }
        if (changed) save();
        return out;
    }

    /** Pushes the current yml state for one player into the MySQL mirror, if it is enabled. */
    public void mirror(UUID uuid, Long playtimeSeconds) {
        var db = plugin.playerDatabase();
        if (db == null) return;
        String name = names.get(uuid);
        String discordId = linkedAccounts.get(uuid);
        String ip = lastConfirmedIp.get(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.upsert(uuid, name, discordId, ip, playtimeSeconds);
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось записать игрока " + uuid + " в БД: " + e.getMessage());
            }
        });
    }

    public record LinkedPlayer(UUID uuid, String name, String discordId, String lastIp) {
    }

    public UUID uuidForDiscordId(String discordId) {
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) return entry.getKey();
        }
        return null;
    }

    /** Все Minecraft-аккаунты (UUID), привязанные к одному Discord - у человека их может быть несколько (альт, смена ника при offline-UUID). */
    public java.util.List<UUID> uuidsForDiscordId(String discordId) {
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) result.add(entry.getKey());
        }
        return result;
    }

    public void unlink(UUID uuid) {
        linkedAccounts.remove(uuid);
        lastConfirmedIp.remove(uuid);
        save();

        var db = plugin.playerDatabase();
        if (db != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.clearDiscord(uuid);
                } catch (Exception e) {
                    plugin.getLogger().warning("Не удалось снять привязку игрока " + uuid + " в БД: " + e.getMessage());
                }
            });
        }
    }

    /**
     * @return true if this IP already passed login confirmation for this player before,
     * meaning confirmation can be skipped this time.
     */
    public boolean isIpConfirmed(UUID uuid, String ip) {
        String confirmed = lastConfirmedIp.get(uuid);
        return confirmed != null && confirmed.equals(ip);
    }

    public void markIpConfirmed(UUID uuid, String ip) {
        lastConfirmedIp.put(uuid, ip);
        save();
        mirror(uuid, null);
    }

    public String createLinkCode(UUID uuid) {
        pendingCodes.values().removeIf(pending -> pending.uuid.equals(uuid));

        String code;
        do {
            code = generateCode();
        } while (pendingCodes.containsKey(code));

        long expiresAt = System.currentTimeMillis() + plugin.config().linkCodeExpiryMinutes() * 60_000L;
        pendingCodes.put(code, new PendingLink(uuid, expiresAt));
        return code;
    }

    /**
     * @return the linked player's UUID if the code was valid and unexpired, otherwise null.
     */
    public UUID confirmLinkCode(String code, String discordId) {
        code = code.trim();
        PendingLink pending = pendingCodes.remove(code);
        if (pending == null) return null;
        if (System.currentTimeMillis() > pending.expiresAt) return null;

        linkedAccounts.put(pending.uuid, discordId);
        save();
        mirror(pending.uuid, null);
        return pending.uuid;
    }

    /**
     * @return true if the given text looks like a link code (used to decide whether a Discord
     * message should be consumed/deleted by the link channel listener).
     */
    public static boolean looksLikeCode(String text) {
        return text != null && text.trim().matches("\\d{4}");
    }

    private String generateCode() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }

    private record PendingLink(UUID uuid, long expiresAt) {
    }
}
