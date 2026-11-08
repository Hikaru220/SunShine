package com.sunshine.plugin.command;

import com.sunshine.plugin.SunShinePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Отдельная короткая команда {@code /profile <ник>} - то же самое, что {@code /sunshine profile},
 * просто без префикса {@code sunshine}, чтобы модераторам не приходилось печатать его каждый раз.
 * Вся логика в {@link SunShineCommand#showProfile}, эта команда - тонкая обёртка.
 */
public class ProfileCommand implements CommandExecutor, TabCompleter {

    private final SunShineCommand delegate;

    public ProfileCommand(SunShinePlugin plugin, SunShineCommand delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Использование: /profile <ник>", NamedTextColor.GRAY));
            return true;
        }
        delegate.showProfile(sender, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("sunshine.profile")) return List.of();
        String prefix = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(prefix))
                .toList();
    }
}
