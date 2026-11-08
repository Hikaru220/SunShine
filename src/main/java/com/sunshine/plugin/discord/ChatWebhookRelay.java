package com.sunshine.plugin.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.Webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отправляет сообщения игрового чата в Discord через channel webhook - по образцу DiscordSRV,
 * т.е. сообщение приходит от лица игрока (его ник и голова скина как аватар), а не от бота.
 * <p>
 * Вебхук ищется/создаётся один раз на канал и кэшируется в памяти ({@link #webhookUrlCache}),
 * чтобы не дёргать Discord API на каждое сообщение чата.
 */
public class ChatWebhookRelay {

    /** Фиксированное имя вебхука - используется и для поиска существующего, и при создании нового. */
    private static final String WEBHOOK_NAME = "SunShine Chat";

    /** Лимит длины сообщения в Discord. */
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private static final String AVATAR_URL_TEMPLATE = "https://mc-heads.net/avatar/%s/100.png";

    private final SunShinePlugin plugin;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final okhttp3.OkHttpClient httpClient;

    /** channelId -> будущий URL вебхука на этом канале. Кэш переживает все последующие вызовы. */
    private final Map<String, CompletableFuture<String>> webhookUrlCache = new ConcurrentHashMap<>();

    public ChatWebhookRelay(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.httpClient = DiscordDns.webhookClient(plugin);
    }

    /**
     * Отправляет сообщение игрока в Discord через webhook указанного канала, от имени игрока
     * (его ник + голова скина в качестве аватарки). Полностью асинхронно - безопасно вызывать
     * из основного потока сервера, сетевые запросы не блокируют вызывающий поток.
     *
     * @param channelId       ID текстового канала Discord, на котором нужно отправить сообщение
     * @param playerName      ник игрока (используется как username вебхука)
     * @param uuid            UUID игрока (используется для получения головы скина через mc-heads.net)
     * @param rawMessageText  текст сообщения (будет обрезан до лимита Discord при необходимости)
     */
    /**
     * Ссылка на голову игрока: по хэшу текстуры скина, который он реально носит сейчас
     * (premium, а также скин SkinsRestorer у пиратов). Если у профиля нет текстуры - по UUID, как раньше.
     */
    public static String avatarUrl(org.bukkit.entity.Player player) {
        try {
            var skin = player.getPlayerProfile().getTextures().getSkin();
            if (skin != null) {
                String path = skin.getPath();
                String hash = path.substring(path.lastIndexOf('/') + 1);
                if (!hash.isBlank()) {
                    String url = "https://mc-heads.net/avatar/" + hash + "/100.png";
                    AVATAR_BY_NAME.put(player.getName().toLowerCase(java.util.Locale.ROOT), url);
                    return url;
                }
            }
        } catch (Throwable ignored) {
            // профиль без текстур - падаем на UUID
        }
        return String.format(AVATAR_URL_TEMPLATE, player.getUniqueId());
    }

    /** Последняя известная голова по нику: нужна для наказаний, когда игрок уже оффлайн. */
    private static final Map<String, String> AVATAR_BY_NAME = new ConcurrentHashMap<>();

    /** Голова по нику: онлайн - из текущего скина, иначе из запомненной при последнем входе, иначе mc-heads по нику. */
    public static String avatarUrlForName(String name) {
        if (name == null || name.isBlank()) return String.format(AVATAR_URL_TEMPLATE, "Steve");
        try {
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayerExact(name);
            if (online != null) return avatarUrl(online);
        } catch (Throwable ignored) {
            // вызвано не из главного потока - достаточно кэша
        }
        String cached = AVATAR_BY_NAME.get(name.toLowerCase(java.util.Locale.ROOT));
        return cached != null ? cached : String.format(AVATAR_URL_TEMPLATE, name);
    }

    public void sendPlayerMessage(String channelId, String playerName, UUID uuid, String rawMessageText) {
        sendPlayerMessage(channelId, playerName, String.format(AVATAR_URL_TEMPLATE, uuid != null ? uuid.toString() : playerName), rawMessageText);
    }

    public void sendPlayerMessage(String channelId, String playerName, String avatarUrl, String rawMessageText) {
        if (channelId == null || channelId.isBlank()) return;
        if (playerName == null || playerName.isBlank()) return;

        String content = truncateToDiscordLimit(rawMessageText == null ? "" : rawMessageText);
        if (content.isBlank()) return;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", content);
        sendViaWebhook(channelId, playerName, avatarUrl, body);
    }

    /**
     * То же самое, но карточкой (embed) вместо голого текста - цветная полоса слева + описание,
     * как в примерах вроде "Помощь" в Discord. Используется для событий (вход/выход/смерть), где
     * хочется не просто строку текста, а оформленное уведомление.
     *
     * @param description текст карточки (поддерживает обычный Discord-markdown)
     * @param color       цвет полосы слева, формат 0xRRGGBB (например 0x57F287 - зелёный)
     */
    public void sendPlayerEmbed(String channelId, String playerName, String avatarUrl, String description, int color) {
        if (channelId == null || channelId.isBlank()) return;
        if (playerName == null || playerName.isBlank()) return;

        ObjectNode body = objectMapper.createObjectNode();
        var embed = body.putArray("embeds").addObject();
        embed.put("description", truncateToDiscordLimit(description == null ? "" : description));
        embed.put("color", color);
        sendViaWebhook(channelId, playerName, avatarUrl, body);
    }

    /** Дозаполняет общие поля (username/avatar_url/allowed_mentions) и шлёт готовое тело через webhook канала. */
    private void sendViaWebhook(String channelId, String playerName, String avatarUrl, ObjectNode body) {
        whenBotReady(() -> resolveWebhookUrl(channelId).thenAccept(webhookUrl -> {
            if (webhookUrl == null) return;
            postToWebhook(webhookUrl, playerName, avatarUrl, body);
        }), 0);
    }

    /** Бот Discord подключается через 15-20 с после старта сервера: события этого окна не теряем, а ждём до 90 с. */
    private void whenBotReady(Runnable action, int attempt) {
        if (plugin.discordBot().isReady()) {
            action.run();
            return;
        }
        if (attempt >= 18 || !plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> whenBotReady(action, attempt + 1), 100L);
    }

    /**
     * Возвращает (из кэша либо ищет/создаёт заново) URL вебхука с именем {@link #WEBHOOK_NAME}
     * на указанном канале. Результат кэшируется на весь срок жизни плагина - повторные вызовы
     * для того же channelId не бьют по Discord API.
     */
    private CompletableFuture<String> resolveWebhookUrl(String channelId) {
        return webhookUrlCache.computeIfAbsent(channelId, this::findOrCreateWebhook);
    }

    private CompletableFuture<String> findOrCreateWebhook(String channelId) {
        CompletableFuture<String> future = new CompletableFuture<>();

        TextChannel channel = plugin.discordBot().jda().getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Не удалось найти текстовый канал Discord " + channelId + " для webhook-чата.");
            future.complete(null);
            return future;
        }

        channel.retrieveWebhooks().queue(webhooks -> {
            Webhook existing = webhooks.stream()
                    .filter(w -> WEBHOOK_NAME.equals(w.getName()))
                    .findFirst()
                    .orElse(null);

            if (existing != null) {
                future.complete(existing.getUrl());
                return;
            }

            channel.createWebhook(WEBHOOK_NAME).queue(
                    created -> future.complete(created.getUrl()),
                    failure -> {
                        plugin.getLogger().warning("Не удалось создать webhook на канале " + channelId
                                + " (нужны права 'Manage Webhooks' у бота): " + failure.getMessage());
                        future.complete(null);
                    }
            );
        }, failure -> {
            plugin.getLogger().warning("Не удалось получить список webhook'ов канала " + channelId
                    + " (нужны права 'Manage Webhooks' у бота): " + failure.getMessage());
            future.complete(null);
        });

        return future;
    }

    /** Дополняет тело общими полями (username/avatar_url/allowed_mentions) и асинхронно POST-ит его на URL вебхука. */
    private void postToWebhook(String webhookUrl, String playerName, String avatarUrl, ObjectNode body) {
        body.put("username", playerName);
        body.put("avatar_url", avatarUrl);
        // Игрок не должен иметь возможность пингануть @everyone/@here/роль/человека, просто
        // написав это текстом в игре - текст всё равно виден, просто без реального пинга.
        body.putObject("allowed_mentions").putArray("parse");

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось сериализовать JSON для webhook-сообщения: " + e.getMessage());
            return;
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(appendWaitParam(webhookUrl))
                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.get("application/json")))
                .build();

        sendWithRetry(request, playerName, 1);
    }

    private static final int MAX_ATTEMPTS = 4;

    /** Короткие попытки подряд: адреса Discord перемешиваются, повтор уходит на другой IP. */
    private void sendWithRetry(okhttp3.Request request, String playerName, int attempt) {
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sendWithRetry(request, playerName, attempt + 1);
                    return;
                }
                plugin.getLogger().warning("Не удалось отправить сообщение в Discord через webhook (" + MAX_ATTEMPTS + " попытки): " + e);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                try (response) {
                    int status = response.code();
                    if (status >= 400) {
                        String text = response.body() != null ? response.body().string() : "";
                        plugin.getLogger().warning("Discord webhook вернул ошибку " + status
                                + " при отправке сообщения игрока " + playerName + ": " + extractErrorMessage(text));
                    }
                }
            }
        });
    }

    private String appendWaitParam(String webhookUrl) {
        return webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true";
    }

    /** Обрезает сообщение до лимита Discord (2000 символов), добавляя многоточие если обрезано. */
    private String truncateToDiscordLimit(String content) {
        if (content.length() <= DISCORD_CONTENT_LIMIT) return content;
        String ellipsis = "...";
        return content.substring(0, DISCORD_CONTENT_LIMIT - ellipsis.length()) + ellipsis;
    }

    /** Пытается вытащить понятное сообщение об ошибке из ответа Discord API, иначе - сырое тело. */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "(пустой ответ)";
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode messageNode = node.get("message");
            if (messageNode != null) return messageNode.asText();
        } catch (Exception ignored) {
            // не JSON - вернём как есть ниже
        }
        return responseBody;
    }
}
