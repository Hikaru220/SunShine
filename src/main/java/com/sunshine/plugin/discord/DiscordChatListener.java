package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;

public class DiscordChatListener extends ListenerAdapter {

    private final SunShinePlugin plugin;

    public DiscordChatListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;
        if (!plugin.config().discordToMinecraftEnabled() || !plugin.config().discordChatEnabled()) return;

        String chatChannelId = plugin.config().chatChannelId();
        if (chatChannelId == null || chatChannelId.isBlank()) return;
        if (!event.getChannel().getId().equals(chatChannelId)) return;

        Message message = event.getMessage();
        String content = message.getContentDisplay();
        // Сообщения, похожие на команду (начинаются с "/"), в игровой чат не пересылаем - это не
        // выполнение команд (сюда никогда не попадёт dispatchCommand), просто чтобы в Discord-канале
        // нельзя было "изображать" ввод игровых команд.
        if (content.startsWith("/")) return;
        if (content.isBlank() && message.getAttachments().isEmpty()) return;
        if (content.isBlank() && !message.getAttachments().isEmpty()) {
            content = "[вложение] " + message.getAttachments().get(0).getUrl();
        }

        String memberName = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getName();

        Component component = MiniMessage.miniMessage().deserialize(
                plugin.config().discordChatFormat(),
                Placeholder.unparsed("member", memberName),
                Placeholder.unparsed("message", content)
        );

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().sendMessage(component));
    }
}
