package com.sunshine.plugin.support;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;

/**
 * Second, independent Discord bot login (Sunshine_Support) hosted inside this same plugin/process,
 * dedicated to support tickets and staff applications. Kept separate from the main SunShine bot so
 * it can keep its own identity, token and roles.
 */
public class SupportBot {

    private final SunShinePlugin plugin;
    private JDA jda;

    public SupportBot(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void start(EventListener... listeners) {
        String token = plugin.config().supportToken();
        if (token == null || token.isBlank() || token.equals("SUPPORT_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("No support bot token configured (support.token) - tickets/applications are disabled.");
            return;
        }

        try {
            // MESSAGE_CONTENT - привилегированный интент: без него текст сообщений приходит пустым,
            // и в логе закрытия тикета остаются только имена авторов. Должен быть включён и в Discord
            // Developer Portal (Bot -> Privileged Gateway Intents -> Message Content Intent).
            jda = JDABuilder.createDefault(token, EnumSet.of(GatewayIntent.MESSAGE_CONTENT))
                    // Свой пул под обработчики: по умолчанию они крутятся на потоке вебсокета, и любой
                    // блокирующий .complete() (создание канала, проверка роли) задерживает ВСЕ остальные
                    // нажатия кнопок дольше 3 секунд -> "Unknown interaction (10062)".
                    .setEventPool(java.util.concurrent.Executors.newFixedThreadPool(4), true)
                    .disableCache(net.dv8tion.jda.api.utils.cache.CacheFlag.VOICE_STATE, net.dv8tion.jda.api.utils.cache.CacheFlag.EMOJI,
                            net.dv8tion.jda.api.utils.cache.CacheFlag.STICKER, net.dv8tion.jda.api.utils.cache.CacheFlag.SCHEDULED_EVENTS)
                    .setHttpClientBuilder(com.sunshine.plugin.discord.DiscordDns.clientBuilder(plugin))                    .addEventListeners(new com.sunshine.plugin.discord.InteractionLagLogger(plugin, "support"))
                    .addEventListeners((Object[]) listeners)
                    .setActivity(Activity.watching("тикеты"))
                    .build();
            jda.awaitReady();
            com.sunshine.plugin.discord.InteractionLagLogger.logPing(plugin, jda, "support");

            for (Guild guild : jda.getGuilds()) {
                guild.updateCommands()
                        .addCommands(
                                Commands.slash("setup-tickets", "Опубликовать панель тикетов поддержки в этом канале"),
                                Commands.slash("setup-applications", "Опубликовать панель заявок на вступление в этом канале")
                        )
                        .queue(
                                success -> plugin.getLogger().info("Support: команды зарегистрированы на сервере " + guild.getName()),
                                failure -> plugin.getLogger().warning("Support: не удалось зарегистрировать команды на "
                                        + guild.getName() + ": " + failure.getMessage())
                        );
            }
            plugin.getLogger().info("Support-бот подключён как " + jda.getSelfUser().getAsTag());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Interrupted while connecting support bot to Discord.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start support bot: " + e.getMessage());
        }
    }

    public void stop() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    public boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public JDA jda() {
        return jda;
    }
}
