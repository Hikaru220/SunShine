package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Прячет из консоли и latest.log простыню ошибок "Failed to establish connection for a node" при старте ботов.
 * Причина - DNS хостинга иногда отдаёт для gateway.discord.gg чужой адрес (сертификат не совпадает);
 * JDA сама повторяет попытку и в итоге подключается, но каждая неудача печатает стектрейс на 20 строк.
 * Вместо них - одна короткая строка не чаще раза в 2 минуты. Включается/выключается в config.yml:
 * network.hide-gateway-cert-noise (по умолчанию true).
 */
public final class GatewayNoiseFilter extends AbstractFilter {

    private static final String LOGGER_SUFFIX = "SessionControllerAdapter";
    private static final String MESSAGE_PART = "Failed to establish connection for a node";
    private static final long NOTE_EVERY_MS = 120_000;

    private final java.util.function.Consumer<String> note;
    private volatile boolean active = true;
    private volatile long lastNote;

    GatewayNoiseFilter(java.util.function.Consumer<String> note) {
        super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL);
        this.note = note;
    }

    /** Вешает фильтр на корневой логгер сервера. */
    public static GatewayNoiseFilter install(SunShinePlugin plugin) {
        GatewayNoiseFilter filter = new GatewayNoiseFilter(msg -> plugin.getLogger().warning(msg));
        try {
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addFilter(filter);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось установить фильтр шума шлюза Discord: " + t);
            filter.active = false;
        }
        return filter;
    }

    /** Фильтр нельзя снять из log4j, поэтому при выключении плагина просто отключаем. */
    public void disable() {
        active = false;
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        if (!active || event == null || event.getLoggerName() == null || event.getMessage() == null) {
            return Filter.Result.NEUTRAL;
        }
        if (event.getLoggerName().endsWith(LOGGER_SUFFIX)
                && event.getMessage().getFormattedMessage().contains(MESSAGE_PART)) {
            long now = System.currentTimeMillis();
            if (now - lastNote > NOTE_EVERY_MS) {
                lastNote = now;
                note.accept("Discord: подключение к шлюзу повторяется (сертификат gateway.discord.gg не совпал - "
                        + "DNS хостинга отдал неверный адрес). Бот подключается сам; подробные ошибки скрыты "
                        + "(network.hide-gateway-cert-noise: false - показать).");
            }
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }
}
