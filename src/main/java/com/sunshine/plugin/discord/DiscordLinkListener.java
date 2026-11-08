package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.link.LinkManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.util.UUID;

/**
 * Listens for bare 4-digit link codes, either in the configured link channel
 * or in a DM to the bot, and confirms pending account links.
 */
public class DiscordLinkListener extends ListenerAdapter {

    private final SunShinePlugin plugin;

    public DiscordLinkListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;

        boolean isDm = event.getChannelType() == ChannelType.PRIVATE;
        String linkChannelId = plugin.config().linkChannelId();
        boolean isLinkChannel = !linkChannelId.isBlank() && event.getChannel().getId().equals(linkChannelId);

        if (!isDm && !isLinkChannel) return;

        Message message = event.getMessage();
        String content = message.getContentRaw();
        if (!LinkManager.looksLikeCode(content)) return;

        LinkManager linkManager = plugin.linkManager();
        UUID linkedUuid = linkManager.confirmLinkCode(content, event.getAuthor().getId());

        if (isLinkChannel) {
            message.delete().queue(null, throwable -> { /* missing perms - ignore */ });
        }

        if (linkedUuid == null) {
            replyEphemerally(event, "❌ Код неверный или устарел. Зайди на сервер ещё раз, чтобы получить новый код.");
            return;
        }

        String playerName = Bukkit.getOfflinePlayer(linkedUuid).getName();
        replyEphemerally(event, "✅ Аккаунт успешно привязан. Теперь зайди на сервер снова.");

        if (plugin.config().notifyOnLinkEnabled() && playerName != null) {
            String notify = plugin.config().notifyOnLinkFormat().replace("{player}", playerName);
            plugin.discordBot().sendToChannel(plugin.config().eventsChannelId(), notify);
        }
    }

    private void replyEphemerally(MessageReceivedEvent event, String text) {
        if (event.getChannelType() == ChannelType.PRIVATE) {
            event.getChannel().sendMessage(text).queue(null, throwable -> { });
        } else {
            event.getAuthor().openPrivateChannel().queue(
                    channel -> channel.sendMessage(text).queue(null, t -> { }),
                    throwable -> { }
            );
        }
    }

    public static void sendInstructions(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Привязка Minecraft аккаунта")
                .setDescription("Привет! Зайди на сервер и скопируй 4-значный код, который увидишь при входе.\n" +
                        "Напиши этот код сюда одним сообщением, например: `1234`.\n" +
                        "После успешной привязки твоё сообщение с кодом будет удалено.")
                .setColor(new Color(88, 101, 242));
        channel.sendMessageEmbeds(embed.build()).queue(null, throwable -> { });
    }
}
