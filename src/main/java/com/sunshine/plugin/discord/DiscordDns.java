package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * DNS для REST-запросов JDA к Discord. На некоторых хостингах системный DNS иногда отдаёт для discord.com
 * чужой адрес (или резолв занимает секунды): запрос подтверждения кнопки уходит не туда, повторяется и не
 * укладывается в 3 секунды Discord - "Приложение не отвечает" (ошибка 10062).
 *
 * Решение: сначала пробуем зашитые адреса Cloudflare, на которых стоит Discord (сертификат при этом
 * проверяется как обычно - имя хоста остаётся прежним), а системный DNS подмешиваем следом, но из кэша,
 * обновляемого в фоне: сам запрос на DNS никогда не блокирует отправку ответа.
 *
 * Это касается только REST. Подключение к шлюзу (WebSocket) идёт через системный DNS: библиотека
 * WebSocket не даёт подменить адрес для wss, поэтому при старте возможны повторные попытки
 * ("certificate does not match gateway.discord.gg") - бот в итоге подключается.
 *
 * Отключается в config.yml: network.pin-discord-ips: false
 */
public final class DiscordDns implements Dns {

    /** Проверено 2026-11-06: сертификат на этих адресах выдан на соответствующее имя хоста. */
    private static final Map<String, List<String>> PINNED = Map.of(
            "discord.com", List.of("162.159.128.233", "162.159.135.232", "162.159.136.232", "162.159.137.232"),
            "gateway.discord.gg", List.of("162.159.130.234", "162.159.133.234", "162.159.134.234", "162.159.135.234"),
            "cdn.discordapp.com", List.of("162.159.129.233", "162.159.130.233", "162.159.133.233", "162.159.134.233"),
            "discordapp.com", List.of("162.159.129.233", "162.159.130.233", "162.159.133.233", "162.159.134.233"),
            "media.discordapp.net", List.of("162.159.128.232", "162.159.129.232", "162.159.130.232", "162.159.133.232"));

    private static final long REFRESH_MS = TimeUnit.MINUTES.toMillis(10);

    private final boolean pin;
    private final Logger log;
    private final Map<String, List<InetAddress>> systemCache = new ConcurrentHashMap<>();
    private final Map<String, Long> refreshedAt = new ConcurrentHashMap<>();
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SunShine-DiscordDns-refresh");
        t.setDaemon(true);
        return t;
    });

    public DiscordDns(boolean pin, Logger log) {
        this.pin = pin;
        this.log = log;
    }

    /** HTTP-клиент для JDA: свой DNS и короткий таймаут подключения (плохой адрес отваливается быстро). */
    public static OkHttpClient.Builder clientBuilder(SunShinePlugin plugin) {
        DiscordDns dns = new DiscordDns(plugin.config().pinDiscordIps(), plugin.getLogger());
        if (dns.pin) {
            plugin.getLogger().info("Discord REST: закреплены проверенные адреса (network.pin-discord-ips: true).");
        }
        return new OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
    }

    /** Клиент для webhook-запросов (чат, решения по заявкам): тот же DNS с закреплёнными адресами, общий общий таймаут вызова 12 с. */
    public static OkHttpClient webhookClient(SunShinePlugin plugin) {
        DiscordDns dns = new DiscordDns(plugin.config().pinDiscordIps(), plugin.getLogger());
        return new OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(3, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String key = hostname.toLowerCase(Locale.ROOT);
        List<String> pinned = pin ? PINNED.get(key) : null;
        if (pinned == null) {
            return Dns.SYSTEM.lookup(hostname);
        }

        List<InetAddress> result = new ArrayList<>();
        List<String> shuffled = new ArrayList<>(pinned);
        Collections.shuffle(shuffled);
        for (String ip : shuffled) {
            // литерал IP - сетевой запрос не делается
            result.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip).getAddress()));
        }
        List<InetAddress> system = systemCache.get(key);
        if (system != null) {
            for (InetAddress a : system) {
                if (result.stream().noneMatch(r -> r.getHostAddress().equals(a.getHostAddress()))) {
                    result.add(a);
                }
            }
        }
        refreshSystemAsync(key, hostname);
        return result;
    }

    private void refreshSystemAsync(String key, String hostname) {
        long now = System.currentTimeMillis();
        Long last = refreshedAt.get(key);
        if (last != null && now - last < REFRESH_MS) {
            return;
        }
        refreshedAt.put(key, now);
        refresher.execute(() -> {
            try {
                systemCache.put(key, Dns.SYSTEM.lookup(hostname));
            } catch (UnknownHostException e) {
                log.fine("Discord DNS: системный резолв " + hostname + " не удался: " + e.getMessage());
            }
        });
    }
}
