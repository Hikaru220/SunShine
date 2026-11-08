package com.sunshine.plugin.court;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;

/**
 * Отдельный Discord-бот суда (свой токен и личность) внутри этого же плагина - как support-бот,
 * но со своим набором команд. Живёт в SunShine, чтобы видеть сервер: списывать монеты, выдавать
 * баны/муты и знать привязки Discord - Minecraft.
 */
public final class CourtBot {

    private final SunShinePlugin plugin;
    private JDA jda;

    public CourtBot(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void start(EventListener... listeners) {
        CourtSettings settings = new CourtSettings(plugin);
        String token = settings.token();
        if (token == null || token.isBlank() || token.equals("COURT_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Court: не задан court.token - бот суда выключен.");
            return;
        }

        try {
            // MESSAGE_CONTENT - привилегированный интент, нужен только для протоколов (court.transcripts).
            EnumSet<GatewayIntent> intents = settings.transcripts()
                    ? EnumSet.of(GatewayIntent.MESSAGE_CONTENT)
                    : EnumSet.noneOf(GatewayIntent.class);
            jda = JDABuilder.createDefault(token, intents)
                    .disableCache(net.dv8tion.jda.api.utils.cache.CacheFlag.VOICE_STATE, net.dv8tion.jda.api.utils.cache.CacheFlag.EMOJI,
                            net.dv8tion.jda.api.utils.cache.CacheFlag.STICKER, net.dv8tion.jda.api.utils.cache.CacheFlag.SCHEDULED_EVENTS)
                    .setHttpClientBuilder(com.sunshine.plugin.discord.DiscordDns.clientBuilder(plugin))                    .addEventListeners(new com.sunshine.plugin.discord.InteractionLagLogger(plugin, "court"))
                    .addEventListeners((Object[]) listeners)
                    .setActivity(Activity.watching("правосудие"))
                    .build();
            jda.awaitReady();
            com.sunshine.plugin.discord.InteractionLagLogger.logPing(plugin, jda, "court");

            for (Guild guild : jda.getGuilds()) {
                guild.updateCommands().addCommands(
                        Commands.slash("court-panel", "Опубликовать панель суда в этом канале")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
                        Commands.slash("fine", "Выставить штраф игроку (судьям)")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "player", "Ник игрока в Minecraft", true),
                                        new OptionData(OptionType.INTEGER, "amount", "Сумма штрафа", true).setMinValue(1),
                                        new OptionData(OptionType.STRING, "reason", "Причина", true),
                                        new OptionData(OptionType.INTEGER, "days", "Срок оплаты в днях", false).setMinValue(1).setMaxValue(30)),
                        Commands.slash("decree", "Вынести постановление против игрока (судьям)")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "player", "Ник игрока в Minecraft", true),
                                        new OptionData(OptionType.STRING, "type", "Вид постановления", true)
                                                .addChoice("Бан", "BAN").addChoice("Мут", "MUTE")
                                                .addChoice("Кик", "KICK").addChoice("Предупреждение", "WARN"),
                                        new OptionData(OptionType.STRING, "reason", "Причина", true),
                                        new OptionData(OptionType.STRING, "duration", "Срок для бана/мута, например 7d или 12h", false)),
                        Commands.slash("dossier", "Судебное досье игрока (судьям)")
                                .addOptions(new OptionData(OptionType.STRING, "player", "Ник игрока в Minecraft", true)),
                        Commands.slash("case", "Показать карточку дела")
                                .addOptions(new OptionData(OptionType.INTEGER, "id", "Номер дела", true).setMinValue(1))
                ).queue(
                        success -> plugin.getLogger().info("Court: команды зарегистрированы на сервере " + guild.getName()),
                        failure -> plugin.getLogger().warning("Court: не удалось зарегистрировать команды на "
                                + guild.getName() + ": " + failure.getMessage()));
            }
            plugin.getLogger().info("Court-бот подключён как " + jda.getSelfUser().getAsTag());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Court: прервано подключение бота суда.");
        } catch (Exception e) {
            plugin.getLogger().severe("Court: не удалось запустить бота суда: " + e.getMessage());
        }
    }

    public void stop() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    public JDA jda() {
        return jda;
    }

    public boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }
}
