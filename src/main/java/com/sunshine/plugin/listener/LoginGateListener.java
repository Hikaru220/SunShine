package com.sunshine.plugin.listener;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LoginGateListener implements Listener {

    private final SunShinePlugin plugin;

    public LoginGateListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        UUID uuid = event.getUniqueId();

        if (plugin.config().requireLink() && !plugin.linkManager().isLinked(uuid)) {
            String code = plugin.linkManager().createLinkCode(uuid);
            String linkChannelId = plugin.config().linkChannelId();
            String channelMention = linkChannelId.isBlank() ? "" : "#" + linkChannelId;
            String message = plugin.config().kickNotLinkedMessage()
                    .replace("{code}", code)
                    .replace("{bot}", plugin.discordBot().botName())
                    .replace("{channel}", channelMention);
            Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
            return;
        }

        if (plugin.config().requireLoginConfirmation() && plugin.discordBot().isReady()) {
            String discordId = plugin.linkManager().discordIdFor(uuid);
            if (discordId == null) return;

            String ip = event.getAddress().getHostAddress();
            if (plugin.linkManager().isIpConfirmed(uuid, ip)) {
                // Already confirmed once from this exact IP - no need to ask again.
                return;
            }

            if (requestConfirmation(event.getName(), ip, discordId)) {
                plugin.linkManager().markIpConfirmed(uuid, ip);
            } else {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Вход не был подтверждён в Discord. Попробуй зайти снова."));
            }
        }
    }

    /**
     * Sends a confirmation DM and blocks (this handler already runs off the main thread)
     * until the linked user confirms, denies, or the timeout elapses.
     */
    private boolean requestConfirmation(String playerName, String ip, String discordId) {
        String token = plugin.loginConfirmationManager().createPending();
        CompletableFuture<Boolean> future = plugin.loginConfirmationManager().futureFor(token);

        User user = plugin.discordBot().jda().retrieveUserById(discordId).complete();
        if (user == null) {
            plugin.loginConfirmationManager().remove(token);
            return true; // can't reach the Discord user, don't lock them out
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Вход подтверждён")
                .setDescription("Подтвердите вход на сервер.")
                .addField("Игрок", playerName, true)
                .addField("IP", ip, true)
                .setColor(new Color(88, 101, 242));

        user.openPrivateChannel().complete()
                .sendMessageEmbeds(embed.build())
                .setActionRow(
                        Button.success("sunshine:loginconfirm:" + token + ":yes", "Подтвердить"),
                        Button.danger("sunshine:loginconfirm:" + token + ":no", "Отклонить")
                )
                .queue(null, throwable -> { });

        try {
            return future.get(plugin.config().loginConfirmationTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            plugin.loginConfirmationManager().remove(token);
        }
    }
}
