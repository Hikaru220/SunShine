package com.sunshine.plugin.console;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;

import java.util.List;

/**
 * Слушает сообщения в консольном Discord-канале и выполняет их как команды консоли
 * сервера - зеркальный аналог "console channel" из DiscordSRV.
 *
 * <p><b>ВНИМАНИЕ: это фактически RCE на сервер через Discord.</b> Единственная защита -
 * проверка роли отправителя на КАЖДОЕ сообщение, без исключений и без доверия отправителю
 * (никакого кеширования "уже проверенных" пользователей - роль могли снять минуту назад).
 * Роль обязана быть явно задана в конфиге ({@code console.command-role-id}); если она
 * пуста - НИЧЕГО не выполняется вообще, даже если писал владелец сервера (fail-closed,
 * а не fail-open).</p>
 */
public class DiscordConsoleListener extends ListenerAdapter {

    private final SunShinePlugin plugin;

    public DiscordConsoleListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;

        String consoleChannelId = plugin.config().consoleChannelId();
        if (consoleChannelId.isBlank() || !event.getChannel().getId().equals(consoleChannelId)) return;

        List<String> roleIds = plugin.config().consoleCommandRoleIds();
        if (roleIds.isEmpty()) {
            // fail-closed: роли не настроены в конфиге - команды из Discord не выполняются
            // никогда. Не логируем/не отвечаем, чтобы не поощрять попытки написать в канал.
            return;
        }

        Member member = event.getMember();
        if (member == null) return; // не резолвится как участник гильдии - игнорируем

        boolean hasRole = member.getRoles().stream().anyMatch(role -> roleIds.contains(role.getId()));
        if (!hasRole) {
            // Тихая реакция вместо текстового ответа - чтобы не шуметь в канале лишними сообщениями.
            event.getMessage().addReaction(Emoji.fromUnicode("❌")).queue(null, throwable -> { });
            return;
        }

        String rawCommand = event.getMessage().getContentRaw();
        if (rawCommand.isBlank()) return;

        // onMessageReceived вызывается в потоке JDA (не на основном потоке сервера) -
        // диспатчим команду через планировщик Bukkit. Отдельно пересылать результат
        // выполнения в Discord не нужно: он и так попадёт туда через ConsoleLogRelay,
        // который слушает весь вывод консоли сервера.
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCommand));
    }
}
