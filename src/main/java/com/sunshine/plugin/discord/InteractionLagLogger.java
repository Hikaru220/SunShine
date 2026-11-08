package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.hooks.EventListener;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Диагностика ошибки "Unknown interaction (10062)": у Discord 3 секунды на ответ. Пишет в лог, СКОЛЬКО
 * мс прошло между нажатием (время создания события на стороне Discord) и моментом, когда событие дошло
 * до плагина, плюс пинги шлюза и REST. Если age большой - события приходят поздно (сеть/DNS сервера),
 * если маленький - значит, дело в чём-то другом (дубль процесса, медленный сам ответ).
 */
public final class InteractionLagLogger implements EventListener {

    private static final long WARN_AGE_MS = 1000;

    private final SunShinePlugin plugin;
    private final String botName;

    public InteractionLagLogger(SunShinePlugin plugin, String botName) {
        this.plugin = plugin;
        this.botName = botName;
    }

    @Override
    public void onEvent(GenericEvent event) {
        if (!(event instanceof GenericInteractionCreateEvent ie)) return;
        long age = ChronoUnit.MILLIS.between(ie.getInteraction().getTimeCreated(), OffsetDateTime.now());
        if (age >= WARN_AGE_MS || age < 0) {
            plugin.getLogger().warning("[lag:" + botName + "] interaction дошло через " + age + " мс после нажатия"
                    + " (пинг шлюза " + event.getJDA().getGatewayPing() + " мс). Лимит Discord - 3000 мс."
                    + (age < 0 ? " Отрицательное значение: часы сервера опережают Discord - сверьте время." : ""));
        }
    }

    /** Отправляет ack и пишет в лог: сколько событие ехало до нас и сколько занял сам запрос подтверждения. */
    public static void timed(SunShinePlugin plugin, String tag, net.dv8tion.jda.api.interactions.Interaction interaction,
                             net.dv8tion.jda.api.requests.RestAction<?> action) {
        final long start = System.nanoTime();
        final long age = ChronoUnit.MILLIS.between(interaction.getTimeCreated(), OffsetDateTime.now());
        action.queue(
                ok -> plugin.getLogger().info("[lag:" + tag + "] событие пришло через " + age + " мс, ack занял "
                        + (System.nanoTime() - start) / 1_000_000 + " мс"),
                err -> plugin.getLogger().warning("[lag:" + tag + "] ack НЕ удался: событие пришло через " + age
                        + " мс, запрос шёл " + (System.nanoTime() - start) / 1_000_000 + " мс, ошибка: " + err.getMessage()));
    }

    /** Один раз после подключения: сколько занимает запрос к Discord REST. */
    public static void logPing(SunShinePlugin plugin, JDA jda, String botName) {
        long gateway = jda.getGatewayPing();
        jda.getRestPing().queue(
                rest -> plugin.getLogger().info("[lag:" + botName + "] пинг шлюза " + gateway + " мс, REST " + rest
                        + " мс" + (rest > 800 || gateway > 800 ? " - МЕДЛЕННО, ответы на кнопки могут не успевать" : " - норма")),
                err -> plugin.getLogger().warning("[lag:" + botName + "] не удалось измерить REST-пинг: " + err.getMessage()));
    }
}
