package com.sunshine.plugin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Сообщения об одобрении/отклонении заявки - в канал заявки от лица вебхука (своё имя и аватар,
 * как "живое" сообщение от сервера, а не от бота). Вебхук создаётся на канале под каждое решение:
 * канал заявки одноразовый и удаляется вместе со своими вебхуками. Нет права "Manage Webhooks" -
 * отправляем обычным сообщением бота, чтобы решение в любом случае дошло.
 */
public final class ApplicationWebhook {

    private static final String WEBHOOK_OBJECT_NAME = "SunShine Applications";
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private final SunShinePlugin plugin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final okhttp3.OkHttpClient http;

    public ApplicationWebhook(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.http = com.sunshine.plugin.discord.DiscordDns.webhookClient(plugin);
    }

    /** @param mentionUserId кого реально пинговать (упоминание в тексте уже должно быть, здесь только разрешение на пинг) */
    public void send(TextChannel channel, String content, String mentionUserId) {
        String text = content.length() <= DISCORD_CONTENT_LIMIT ? content : content.substring(0, DISCORD_CONTENT_LIMIT - 3) + "...";
        channel.createWebhook(WEBHOOK_OBJECT_NAME).queue(
                webhook -> post(webhook.getUrl(), channel, text, mentionUserId),
                failure -> {
                    plugin.getLogger().warning("Заявки: не удалось создать вебхук в канале " + channel.getId()
                            + " (нужно право 'Manage Webhooks'), отправляю обычным сообщением: " + failure.getMessage());
                    channel.sendMessage(text).queue(null, t -> { });
                });
    }

    private void post(String webhookUrl, TextChannel channel, String text, String mentionUserId) {
        String avatar = plugin.config().applicationsWebhookAvatarUrl();
        if (avatar.isBlank() && plugin.discordBot().jda() != null) {
            avatar = plugin.discordBot().jda().getSelfUser().getEffectiveAvatarUrl();
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("content", text);
        body.put("username", plugin.config().applicationsWebhookName());
        if (!avatar.isBlank()) body.put("avatar_url", avatar);
        var allowed = body.putObject("allowed_mentions");
        allowed.putArray("parse");
        if (mentionUserId != null && !mentionUserId.isBlank()) allowed.putArray("users").add(mentionUserId);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            plugin.getLogger().warning("Заявки: не удалось собрать JSON вебхука: " + e.getMessage());
            channel.sendMessage(text).queue(null, t -> { });
            return;
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true")
                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.get("application/json")))
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                plugin.getLogger().warning("Заявки: не удалось отправить вебхук: " + e);
                channel.sendMessage(text).queue(null, t -> { });
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                try (response) {
                    if (response.code() >= 400) {
                        String bodyText = response.body() != null ? response.body().string() : "";
                        plugin.getLogger().warning("Заявки: вебхук вернул " + response.code() + ": " + bodyText
                                + " - отправляю обычным сообщением бота.");
                        channel.sendMessage(text).queue(null, t -> { });
                    } else {
                        plugin.getLogger().info("Заявки: решение отправлено ВЕБХУКОМ в канал " + channel.getName() + ".");
                    }
                }
            }
        });
    }
}
