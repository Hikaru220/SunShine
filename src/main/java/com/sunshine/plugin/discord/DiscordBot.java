package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.List;

import java.util.EnumSet;

public class DiscordBot {

    private final SunShinePlugin plugin;
    private JDA jda;

    public DiscordBot(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Runnable onReady, EventListener... listeners) {
        String token = plugin.config().botToken();
        if (token == null || token.isBlank() || token.equals("BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("No Discord bot token configured in config.yml - Discord features are disabled.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(token,
                            EnumSet.of(
                                    GatewayIntent.GUILD_MESSAGES,
                                    GatewayIntent.MESSAGE_CONTENT,
                                    GatewayIntent.GUILD_MEMBERS,
                                    GatewayIntent.DIRECT_MESSAGES
                            ))
                    .disableCache(net.dv8tion.jda.api.utils.cache.CacheFlag.VOICE_STATE, net.dv8tion.jda.api.utils.cache.CacheFlag.EMOJI,
                            net.dv8tion.jda.api.utils.cache.CacheFlag.STICKER, net.dv8tion.jda.api.utils.cache.CacheFlag.SCHEDULED_EVENTS)
                    .setHttpClientBuilder(DiscordDns.clientBuilder(plugin))                    .addEventListeners(new InteractionLagLogger(plugin, "main"))
                    .addEventListeners((Object[]) listeners)
                    .setActivity(Activity.playing("запуск..."))
                    .build();
            jda.awaitReady();
            InteractionLagLogger.logPing(plugin, jda, "main");
            // Registered per-guild (not globally) so the command shows up instantly instead of
            // taking up to an hour to propagate, as global commands do.
            List<CommandData> commands = new ArrayList<>();
            commands.add(Commands.slash("apply-panel", "Опубликовать панель заявок в этом канале"));
            if (plugin.config().profileEnabled()) {
                commands.add(Commands.slash("profile", "Показать профиль игрока (ник, привязка, группа, модерация)")
                        .addOption(OptionType.STRING, "nickname", "Ник игрока", true));
            }
            if (plugin.config().territoryEnabled()) {
                commands.add(Commands.slash("setup-territory-panel", "Опубликовать панель управления общинами в этом канале"));
            }
            for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                guild.updateCommands()
                        .addCommands(commands)
                        .queue(
                                success -> plugin.getLogger().info("Slash-команды зарегистрированы на сервере: " + guild.getName()),
                                failure -> plugin.getLogger().warning("Не удалось зарегистрировать команды на сервере "
                                        + guild.getName() + ": " + failure.getMessage())
                        );
            }
            onReady.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Interrupted while connecting to Discord.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start Discord bot: " + e.getMessage());
        }
    }

    public void stop() {
        if (jda != null) {
            jda.shutdownNow();
            try {
                // Block until JDA's background threads (e.g. the WebSocket reader) have fully
                // stopped. Otherwise Bukkit closes this plugin's jar while those threads are
                // still running, and any lazy class load they trigger blows up with a
                // "zip file closed" error.
                jda.awaitShutdown(java.time.Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jda = null;
        }
    }

    public boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public JDA jda() {
        return jda;
    }

    public String botName() {
        return isReady() ? jda.getSelfUser().getName() : "SunShine";
    }

    public void sendToChannel(String channelId, String message) {
        if (!isReady() || channelId == null || channelId.isBlank()) return;
        MessageChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Discord channel with id " + channelId + " could not be found.");
            return;
        }
        // Игроки (ник, чат-сообщение) не должны иметь возможность пингануть @everyone/@here/роль/
        // человека, просто написав это текстом в игре - отключаем разбор упоминаний для всего,
        // что уходит через этот метод (текст всё равно виден, просто без реального пинга).
        channel.sendMessage(message)
                .setAllowedMentions(java.util.Collections.emptyList())
                .queue(null, throwable ->
                        plugin.getLogger().warning("Failed to send message to Discord: " + throwable.getMessage()));
    }

    public void sendEmbedToChannel(String channelId, MessageEmbed embed) {
        if (!isReady() || channelId == null || channelId.isBlank()) return;
        MessageChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Discord channel with id " + channelId + " could not be found.");
            return;
        }
        channel.sendMessageEmbeds(embed)
                .setAllowedMentions(java.util.Collections.emptyList())
                .queue(null, throwable ->
                        plugin.getLogger().warning("Failed to send embed to Discord: " + throwable.getMessage()));
    }

    /** Личное сообщение (embed) конкретному пользователю Discord. Тихо ничего не делает, если ЛС закрыты/бот не в общих серверах. */
    public void sendDirectMessage(String discordId, MessageEmbed embed) {
        if (!isReady() || discordId == null || discordId.isBlank()) return;
        // Раньше ошибки глотались молча - не было видно, почему ЛС не дошло. Теперь одна строка в логе.
        jda.retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        dm -> dm.sendMessageEmbeds(embed).queue(
                                ok -> plugin.getLogger().info("ЛС отправлено пользователю Discord " + discordId + "."),
                                throwable -> plugin.getLogger().info("ЛС пользователю Discord " + discordId
                                        + " не доставлено (скорее всего закрыты личные сообщения): " + throwable.getMessage())),
                        throwable -> plugin.getLogger().info("Не удалось открыть ЛС с пользователем Discord " + discordId
                                + ": " + throwable.getMessage())),
                throwable -> plugin.getLogger().info("Пользователь Discord " + discordId + " не найден, ЛС не отправлено: "
                        + throwable.getMessage()));
    }

    public void updateStatus(String status) {
        if (!isReady()) return;
        jda.getPresence().setActivity(Activity.playing(status));
    }

    /**
     * Sets a user's Discord server nickname, searching every guild the bot is in for one where
     * they're a member. Fully async - safe to call from the main server thread.
     */
    public void setNickname(String discordUserId, String nickname) {
        if (!isReady() || discordUserId == null || discordUserId.isBlank() || nickname == null || nickname.isBlank()) {
            return;
        }

        for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
            guild.retrieveMemberById(discordUserId).queue(
                    member -> {
                        // modifyNickname() сам делает проверку иерархии ролей СИНХРОННО и кидает
                        // HierarchyException прямо тут (не в колбэке .queue()), если у участника
                        // роль выше или равна роли бота - например, если это сам владелец сервера
                        // или админ. Это ожидаемый случай (бот физически не может менять ник таким
                        // людям), а не ошибка - гасим тут, чтобы JDA не сыпал стектрейс в лог.
                        try {
                            guild.modifyNickname(member, nickname).queue(null, throwable ->
                                    plugin.getLogger().warning("Не удалось изменить ник " + discordUserId + ": " + throwable.getMessage()));
                        } catch (net.dv8tion.jda.api.exceptions.HierarchyException e) {
                            plugin.getLogger().info("Не меняю ник " + discordUserId + " - у него роль выше или равна роли бота.");
                        } catch (Exception e) {
                            plugin.getLogger().warning("Не удалось изменить ник " + discordUserId + ": " + e.getMessage());
                        }
                    },
                    throwable -> { /* not a member of this guild, try others */ }
            );
        }
    }

    /**
     * Gives the given Discord role to a user, searching every guild the bot is in for one that
     * has both the role and the member. Fully async - safe to call from the main server thread.
     */
    public void giveRole(String discordUserId, String roleId) {
        if (!isReady() || roleId == null || roleId.isBlank()) return;

        for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
            net.dv8tion.jda.api.entities.Role role = guild.getRoleById(roleId);
            if (role == null) continue;

            guild.retrieveMemberById(discordUserId).queue(
                    member -> guild.addRoleToMember(member, role).queue(null, throwable ->
                            plugin.getLogger().warning("Не удалось выдать роль " + roleId + ": " + throwable.getMessage())),
                    throwable -> { /* not a member of this guild, try others */ }
            );
        }
    }

    /**
     * Removes the given Discord role from a user, searching every guild the bot is in for one
     * that has both the role and the member. Fully async - safe to call from the main server thread.
     */
    public void removeRole(String discordUserId, String roleId) {
        if (!isReady() || roleId == null || roleId.isBlank()) return;

        for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
            net.dv8tion.jda.api.entities.Role role = guild.getRoleById(roleId);
            if (role == null) continue;

            guild.retrieveMemberById(discordUserId).queue(
                    member -> guild.removeRoleFromMember(member, role).queue(null, throwable ->
                            plugin.getLogger().warning("Не удалось снять роль " + roleId + ": " + throwable.getMessage())),
                    throwable -> { /* not a member of this guild, try others */ }
            );
        }
    }
}
