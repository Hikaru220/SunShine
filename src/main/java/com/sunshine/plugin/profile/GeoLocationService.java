package com.sunshine.plugin.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.plugin.SunShinePlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Геолокация по IP через бесплатный ip-api.com (без ключа, до 45 запросов/мин) - напрямую, в
 * обход команды FlectonePulse {@code /geolocate}. Так надёжнее: у неё вывод рассчитан на игровой
 * чат (цвета/клики), а через консоль/Discord-relay это превращается в нечитаемую кашу из кодов
 * форматирования. Здесь - чистые структурированные данные (страна/город/провайдер), которые
 * можно нормально показать и в embed, и в чате.
 */
public final class GeoLocationService {

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,message,country,regionName,city,isp,proxy,hosting&lang=ru";

    private final SunShinePlugin plugin;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public GeoLocationService(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<GeoInfo> lookup(String ip) {
        if (ip == null || ip.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(API_URL, ip)))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(response.body()))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Geolocate: запрос к ip-api.com не удался: " + throwable.getMessage());
                    return null;
                });
    }

    private GeoInfo parse(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            if (!"success".equals(node.path("status").asText())) {
                plugin.getLogger().warning("Geolocate: ip-api.com ответил ошибкой: " + node.path("message").asText());
                return null;
            }
            return new GeoInfo(
                    node.path("country").asText(null),
                    node.path("regionName").asText(null),
                    node.path("city").asText(null),
                    node.path("isp").asText(null),
                    node.path("proxy").asBoolean(false),
                    node.path("hosting").asBoolean(false)
            );
        } catch (Exception e) {
            plugin.getLogger().warning("Geolocate: не удалось разобрать ответ ip-api.com: " + e.getMessage());
            return null;
        }
    }

    public record GeoInfo(String country, String region, String city, String isp, boolean proxy, boolean hosting) {

        /** Готовая для показа строка вида "Россия, Москва, Москва (ISP: ...) [VPN/proxy]". */
        public String format() {
            StringBuilder sb = new StringBuilder();
            if (country != null) sb.append(country);
            if (region != null && !region.equals(city)) sb.append(", ").append(region);
            if (city != null) sb.append(", ").append(city);
            if (isp != null) sb.append(" (").append(isp).append(")");
            if (proxy) sb.append(" ⚠ VPN/proxy");
            if (hosting) sb.append(" ⚠ хостинг/датацентр");
            return sb.isEmpty() ? "неизвестно" : sb.toString();
        }
    }
}
