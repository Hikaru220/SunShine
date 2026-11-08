package com.sunshine.plugin.application;

import com.sunshine.plugin.SunShinePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApplicationManager {

    private final SunShinePlugin plugin;
    private final File storageFile;
    private final Map<String, Application> applications = new ConcurrentHashMap<>();

    public ApplicationManager(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "applications.yml");
        load();
    }

    private void load() {
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        for (String discordId : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(discordId);
            if (section == null) continue;
            Application application = new Application(
                    discordId,
                    section.getString("discordTag", ""),
                    section.getString("minecraftName", ""),
                    section.getString("age", ""),
                    section.getString("source", ""),
                    section.getString("about", ""),
                    section.getString("rulesAccepted", ""),
                    section.getLong("submittedAt"),
                    ApplicationStatus.valueOf(section.getString("status", "PENDING")),
                    section.getString("decidedBy", null)
            );
            application.setJoinRoleGranted(section.getBoolean("joinRoleGranted", false));
            application.setDeclineReason(section.getString("declineReason", null));
            application.setPlans(section.getString("plans", ""));
            application.setChannelId(section.getString("channelId", null));
            applications.put(discordId, application);
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Application application : applications.values()) {
            String path = application.discordId();
            yaml.set(path + ".discordTag", application.discordTag());
            yaml.set(path + ".minecraftName", application.minecraftName());
            yaml.set(path + ".age", application.age());
            yaml.set(path + ".source", application.source());
            yaml.set(path + ".about", application.about());
            yaml.set(path + ".rulesAccepted", application.rulesAccepted());
            yaml.set(path + ".submittedAt", application.submittedAt());
            yaml.set(path + ".status", application.status().name());
            yaml.set(path + ".decidedBy", application.decidedBy());
            yaml.set(path + ".joinRoleGranted", application.joinRoleGranted());
            yaml.set(path + ".declineReason", application.declineReason());
            yaml.set(path + ".plans", application.plans());
            yaml.set(path + ".channelId", application.channelId());
        }
        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save applications.yml: " + e.getMessage());
        }
    }

    public Application get(String discordId) {
        return applications.get(discordId);
    }

    public java.util.Collection<Application> all() {
        return applications.values();
    }

    public boolean canApply(String discordId) {
        Application existing = applications.get(discordId);
        return existing == null || existing.status() == ApplicationStatus.DECLINED;
    }

    /** Согласие с правилами в анкету не входит - его спрашивают в канале заявки, поэтому rulesAccepted пустое. */
    public Application submit(String discordId, String discordTag, String minecraftName,
                               String age, String source, String plans, String about) {
        Application application = new Application(discordId, discordTag, minecraftName, age, source, about,
                "", System.currentTimeMillis(), ApplicationStatus.PENDING, null);
        application.setPlans(plans);
        applications.put(discordId, application);
        save();
        return application;
    }

    public void setChannelId(String discordId, String channelId) {
        Application application = applications.get(discordId);
        if (application == null) return;
        application.setChannelId(channelId);
        save();
    }

    public void decide(String discordId, ApplicationStatus status, String decidedBy) {
        Application application = applications.get(discordId);
        if (application == null) return;
        application.setStatus(status);
        application.setDecidedBy(decidedBy);
        save();
    }

    /**
     * Finds an accepted application for the given Minecraft nickname (case-insensitive) whose
     * join-role hasn't been granted yet.
     */
    public Application findAcceptedUngrantedByNickname(String minecraftName) {
        for (Application application : applications.values()) {
            if (application.status() == ApplicationStatus.ACCEPTED
                    && !application.joinRoleGranted()
                    && application.minecraftName().equalsIgnoreCase(minecraftName)) {
                return application;
            }
        }
        return null;
    }

    /**
     * Finds the most recently submitted application for the given Minecraft nickname
     * (case-insensitive), regardless of status. Used by /profile to show who reviewed a player's
     * application. Returns null if the player never applied.
     */
    public Application findLatestByNickname(String minecraftName) {
        Application latest = null;
        for (Application application : applications.values()) {
            if (!application.minecraftName().equalsIgnoreCase(minecraftName)) continue;
            if (latest == null || application.submittedAt() > latest.submittedAt()) {
                latest = application;
            }
        }
        return latest;
    }

    public void markJoinRoleGranted(String discordId) {
        Application application = applications.get(discordId);
        if (application == null) return;
        application.setJoinRoleGranted(true);
        save();
    }

    public void setDeclineReason(String discordId, String reason) {
        Application application = applications.get(discordId);
        if (application == null) return;
        application.setDeclineReason(reason);
        save();
    }
}
