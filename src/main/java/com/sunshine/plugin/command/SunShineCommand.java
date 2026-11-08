package com.sunshine.plugin.command;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.migration.FlectonePulseMigrator;
import com.sunshine.plugin.profile.ModerationExecutor;
import com.sunshine.plugin.profile.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class SunShineCommand implements CommandExecutor, TabCompleter {

    private final SunShinePlugin plugin;

    public SunShineCommand(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/sunshine <reload|status|link|unlink>", NamedTextColor.GRAY));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "link" -> handleLink(sender);
            case "unlink" -> handleUnlink(sender);
            case "db-sync" -> handleDbSync(sender);
            case "backup" -> handleBackup(sender);
            case "leave-community" -> handleLeaveCommunity(sender, args);
            case "admin-unlink" -> handleAdminUnlink(sender, args);
            case "profile" -> handleProfile(sender, args);
            case "punish" -> handlePunish(sender, args);
            case "lookup" -> handleLookup(sender, args);
            case "pass" -> handlePass(sender, args);
            case "sync" -> handleSync(sender, args);
            case "migrate-flectonepulse" -> handleMigrateFlectonePulse(sender, args);
            default -> sender.sendMessage(Component.text("/sunshine <reload|status|link|unlink>", NamedTextColor.GRAY));
        }
        return true;
    }

 /** Покупка проходки: /sunshine pass <ник> (консоль или sunshine.admin). */
    private void handlePass(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9_]{2,16}")) {
            sender.sendMessage(Component.text("/sunshine pass <ник>", NamedTextColor.GRAY));
            return;
        }
        if (plugin.applicationListener() == null || plugin.discordBot() == null) {
            sender.sendMessage(Component.text("Discord-бот не запущен.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(plugin.applicationListener().approveByPurchase(args[1]), NamedTextColor.GREEN));
    }

    /** Покупка донат-группы: /sunshine sync <ник> - сразу обновить Discord-роли по группам LuckPerms. */
    private void handleSync(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/sunshine sync <ник>", NamedTextColor.GRAY));
            return;
        }
        java.util.UUID uuid = plugin.linkManager().uuidForName(args[1]);
        if (uuid == null) {
            sender.sendMessage(Component.text("Игрок не привязан к Discord: " + args[1], NamedTextColor.RED));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.groupSyncManager().syncPlayer(uuid));
        sender.sendMessage(Component.text("Синхронизация ролей запущена для " + args[1], NamedTextColor.GREEN));
    }

    /** Выход игрока из своей общины прямо в игре (то же, что кнопка "Покинуть общину" в Discord). Нужно подтверждение, чтобы не выйти случайно. */
    private void handleLeaveCommunity(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эту команду может использовать только игрок.", NamedTextColor.RED));
            return;
        }
        var manager = plugin.communityManager();
        if (manager == null) {
            sender.sendMessage(Component.text("Модуль общин сейчас недоступен.", NamedTextColor.RED));
            return;
        }
        var community = manager.findCommunityForPlayerCached(player.getUniqueId());
        if (community == null) {
            sender.sendMessage(Component.text("Ты не состоишь в общине.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage(Component.text("Ты выходишь из общины " + community.name() + ". Чтобы подтвердить, напиши: ", NamedTextColor.GRAY)
                    .append(Component.text("/sunshine leave-community confirm", NamedTextColor.AQUA)));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var result = manager.leaveCommunity(player.getUniqueId());
            sender.sendMessage(result.isOk()
                    ? Component.text("Ты вышел из общины " + community.name() + ".", NamedTextColor.GREEN)
                    : Component.text(result.error(), NamedTextColor.RED));
        });
    }

    /** Ручной бэкап БД прямо сейчас (в дополнение к автобэкапу по расписанию). */
    private void handleBackup(CommandSender sender) {
        if (!sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Делаю бэкап БД…", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.io.File file = plugin.databaseBackupService().runNow();
                sender.sendMessage(Component.text("Готово: " + plugin.config().backupDirectory() + "/" + file.getName()
                        + " (" + file.length() / 1024 + " КБ)", NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Бэкап не удался: " + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    private void handleDbSync(CommandSender sender) {
        if (!sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (!plugin.config().playerDbEnabled()) {
            sender.sendMessage(Component.text("player-db выключен в config.yml.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Синхронизирую все привязки с БД игроков…", NamedTextColor.GRAY));
        plugin.syncPlayerDatabase(sender);
    }

    /**
     * Разовый перенос БД FlectonePulse из H2 в MySQL (ту же, что player-db). Запускать ТОЛЬКО когда
     * jar FlectonePulse убран из plugins/ и сервер перезапущен без него - иначе H2-файл занят
     * самим FlectonePulse, второе подключение рискует его повредить. См. GUIDE.md.
     */
    private void handleMigrateFlectonePulse(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Использование: /sunshine migrate-flectonepulse <путь без .mv.db> [h2-юзер] [h2-пароль]", NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    "ВНИМАНИЕ: сначала убери jar FlectonePulse из plugins/ и перезапусти сервер - см. GUIDE.md.", NamedTextColor.RED));
            return;
        }

        String h2Path = args[1];
        // FlectonePulse не передаёт H2 никаких учётных данных (пустые Properties в HikariConfig),
        // поэтому у файла пустые user/password - это НЕ "sa", как обычно по умолчанию у H2.
        String h2User = args.length > 2 ? args[2] : "";
        String h2Password = args.length > 3 ? args[3] : "";

        sender.sendMessage(Component.text("Начинаю перенос H2 -> MySQL, смотри консоль/чат...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                new FlectonePulseMigrator(plugin).migrate(h2Path, h2User, h2Password, sender));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        plugin.reload();
        sender.sendMessage(Component.text("SunShine конфигурация перезагружена.", NamedTextColor.GREEN));
    }

    private void handleStatus(CommandSender sender) {
        boolean ready = plugin.discordBot().isReady();
        sender.sendMessage(Component.text(
                ready ? "Discord бот подключён." : "Discord бот не подключён.",
                ready ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleLink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эту команду может использовать только игрок.", NamedTextColor.RED));
            return;
        }
        if (plugin.linkManager().isLinked(player.getUniqueId())) {
            sender.sendMessage(Component.text("Твой аккаунт уже привязан к Discord.", NamedTextColor.YELLOW));
            return;
        }

        String code = plugin.linkManager().createLinkCode(player.getUniqueId());
        player.sendMessage(Component.text("Твой код: ", NamedTextColor.GRAY)
                .append(Component.text(code, NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Напиши его боту " + plugin.discordBot().botName() + " в личные сообщения (ЛС).", NamedTextColor.GRAY));
    }

    private void handleUnlink(CommandSender sender) {
        UUID target;
        if (sender instanceof Player player) {
            target = player.getUniqueId();
        } else {
            sender.sendMessage(Component.text("Эту команду может использовать только игрок.", NamedTextColor.RED));
            return;
        }

        if (!plugin.linkManager().isLinked(target)) {
            sender.sendMessage(Component.text("Твой аккаунт не привязан.", NamedTextColor.YELLOW));
            return;
        }
        plugin.linkManager().unlink(target);
        sender.sendMessage(Component.text("Аккаунт отвязан от Discord.", NamedTextColor.GREEN));
    }

    /** Force-unlinks another player's Discord account by nickname - for staff when the player can't do it themselves. */
    private void handleAdminUnlink(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sunshine.admin")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /sunshine admin-unlink <ник>", NamedTextColor.GRAY));
            return;
        }

        String nickname = args[1];
        UUID uuid = plugin.linkManager().uuidForName(nickname);
        if (uuid == null) {
            // only the server's own cache - no blocking Mojang lookup on the main thread
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(nickname);
            uuid = offline == null ? null : offline.getUniqueId();
        }

        if (uuid == null || !plugin.linkManager().isLinked(uuid)) {
            sender.sendMessage(Component.text("Игрок '" + nickname + "' не найден или не привязан.", NamedTextColor.YELLOW));
            return;
        }

        plugin.linkManager().unlink(uuid);
        sender.sendMessage(Component.text("Привязка Discord для '" + nickname + "' снята.", NamedTextColor.GREEN));
    }

    /** Показывает карточку профиля игрока: ник, привязка Discord, время онлайна, группа LuckPerms, кто принял заявку. */
    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /sunshine profile <ник>", NamedTextColor.GRAY));
            return;
        }
        showProfile(sender, args[1]);
    }

    /** Общая логика для /profile и /sunshine profile - см. {@link ProfileCommand}. */
    public void showProfile(CommandSender sender, String nickname) {
        if (!sender.hasPermission("sunshine.profile")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (!plugin.config().profileEnabled()) {
            sender.sendMessage(Component.text("profile выключен в config.yml.", NamedTextColor.RED));
            return;
        }

        new ProfileService(plugin).load(nickname).whenComplete((profile, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        sender.sendMessage(Component.text("Ошибка сбора данных — смотри консоль.", NamedTextColor.RED));
                        plugin.getLogger().warning("/profile: " + throwable.getMessage());
                        return;
                    }
                    if (profile == null) {
                        sender.sendMessage(Component.text("Игрок '" + nickname + "' не найден.", NamedTextColor.YELLOW));
                        return;
                    }
                    sendProfileCard(sender, profile);
                }));
    }

    private void sendProfileCard(CommandSender sender, ProfileService.Profile profile) {
        sender.sendMessage(Component.text("── Профиль: " + profile.nickname() + " ──", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Discord: ", NamedTextColor.GRAY)
                .append(Component.text(profile.discordId() == null ? "не привязан" : profile.discordId(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Сейчас: ", NamedTextColor.GRAY)
                .append(Component.text(profile.online() ? "онлайн" : "офлайн", profile.online() ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("Вайтлист: ", NamedTextColor.GRAY)
                .append(Component.text(profile.whitelisted() ? "да" : "нет", profile.whitelisted() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Время онлайна: ", NamedTextColor.GRAY)
                .append(Component.text(formatPlaytime(profile.playtimeSeconds()), NamedTextColor.WHITE)));

        String group = profile.groupName() == null ? "неизвестно" : profile.groupName();
        sender.sendMessage(Component.text("Группа: ", NamedTextColor.GRAY).append(Component.text(group, NamedTextColor.WHITE)));

        String applicationLine = profile.applicationStatus() == null ? "не подавал(а)"
                : profile.applicationStatus() + (profile.applicationDecidedBy() == null ? "" : " (принял: " + profile.applicationDecidedBy() + ")");
        sender.sendMessage(Component.text("Заявка: ", NamedTextColor.GRAY).append(Component.text(applicationLine, NamedTextColor.WHITE)));

        sendPunishmentHistory(sender, profile.punishments());

        String nickname = profile.nickname();
        sender.sendMessage(Component.text("Действия: ", NamedTextColor.GRAY)
                .append(suggestButton("[Бан]", NamedTextColor.RED, "/sunshine punish ban " + nickname + " "))
                .append(Component.text("  "))
                .append(suggestButton("[Мут]", NamedTextColor.RED, "/sunshine punish mute " + nickname + " "))
                .append(Component.text("  "))
                .append(suggestButton("[Кик]", NamedTextColor.YELLOW, "/sunshine punish kick " + nickname + " "))
                .append(Component.text("  "))
                .append(suggestButton("[Варн]", NamedTextColor.YELLOW, "/sunshine punish warn " + nickname + " ")));

        Component infoLine = Component.text("Инфо: ", NamedTextColor.GRAY)
                .append(runButton("[Баны]", NamedTextColor.AQUA, "/sunshine lookup banlist " + nickname))
                .append(Component.text("  "))
                .append(runButton("[Муты]", NamedTextColor.AQUA, "/sunshine lookup mutelist " + nickname))
                .append(Component.text("  "))
                .append(runButton("[Варны]", NamedTextColor.AQUA, "/sunshine lookup warnlist " + nickname));
        // IP/геолокация доступны только если известен последний IP (нужен player-db)
        if (profile.lastIp() != null && !profile.lastIp().isBlank()) {
            infoLine = infoLine.append(Component.text("  "))
                    .append(runButton("[Геолокация]", NamedTextColor.AQUA, "/sunshine lookup geolocate " + nickname))
                    .append(Component.text("  "))
                    .append(copyButton("[IP]", NamedTextColor.AQUA, profile.lastIp()));
        }
        sender.sendMessage(infoLine);
    }

    /** Компактный список последних наказаний из БД FlectonePulse (см. GUIDE.md раздел 10 про перенос на MySQL). */
    private void sendPunishmentHistory(CommandSender sender, List<com.sunshine.plugin.profile.FlectonePulseHistory.PunishmentRecord> punishments) {
        if (punishments.isEmpty()) {
            sender.sendMessage(Component.text("История наказаний: ", NamedTextColor.GRAY)
                    .append(Component.text("нет данных (или FlectonePulse ещё не на MySQL)", NamedTextColor.WHITE)));
            return;
        }
        sender.sendMessage(Component.text("История наказаний:", NamedTextColor.GRAY));
        for (var p : punishments) {
            NamedTextColor color = p.active() ? NamedTextColor.RED : NamedTextColor.DARK_GRAY;
            String status = p.active() ? "активно" : "снято/истекло";
            String line = "  " + p.type() + " от " + p.moderatorName() + " (" + status + ")"
                    + (p.reason() == null || p.reason().isBlank() ? "" : ": " + p.reason());
            sender.sendMessage(Component.text(line, color));
        }
    }

    private Component suggestButton(String label, NamedTextColor color, String suggestedCommand) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.suggestCommand(suggestedCommand))
                .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы подставить команду в чат")));
    }

    /** Кнопка, которая выполняется сразу по клику - без ввода срока/причины (list-команды). */
    private Component runButton(String label, NamedTextColor color, String command) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы выполнить сразу")));
    }

    /** Кнопка, которая копирует текст в буфер обмена по клику - для IP и подобного. */
    private Component copyButton(String label, NamedTextColor color, String textToCopy) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.copyToClipboard(textToCopy))
                .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы скопировать: " + textToCopy)));
    }

    private String formatPlaytime(Long seconds) {
        if (seconds == null) return "неизвестно";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "ч " + minutes + "м";
    }

    /** Выполняет команду модерации FlectonePulse от лица консоли. См. profile.commands.* в config.yml. */
    private void handlePunish(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sunshine.profile")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text(
                    "Использование: /sunshine punish <ban|mute|kick|warn> <ник> [срок|-1] [причина]", NamedTextColor.GRAY));
            return;
        }

        String actionArg = args[1].toLowerCase();
        String nickname = args[2];
        ModerationExecutor.Action action = switch (actionArg) {
            case "ban" -> ModerationExecutor.Action.BAN;
            case "mute" -> ModerationExecutor.Action.MUTE;
            case "kick" -> ModerationExecutor.Action.KICK;
            case "warn" -> ModerationExecutor.Action.WARN;
            default -> null;
        };
        if (action == null) {
            sender.sendMessage(Component.text("Неизвестное действие '" + actionArg + "' (ban|mute|kick|warn).", NamedTextColor.RED));
            return;
        }

        boolean needsDuration = action == ModerationExecutor.Action.BAN || action == ModerationExecutor.Action.MUTE;
        String duration = null;
        String reason;
        if (needsDuration) {
            if (args.length < 4) {
                sender.sendMessage(Component.text("Укажи срок (например 1d2h, или -1 для навсегда).", NamedTextColor.RED));
                return;
            }
            duration = args[3];
            reason = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : null;
        } else {
            reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
        }

        new ModerationExecutor(plugin).execute(action, nickname, duration, reason, sender.getName());
        sender.sendMessage(Component.text("Выполнено: " + actionArg + " → " + nickname, NamedTextColor.GREEN));
    }

    /**
     * Выполняет команду-просмотр FlectonePulse (banlist/mutelist/warnlist) от лица консоли - не
     * требует срока/причины, поэтому запускается сразу по клику на кнопку в /sunshine profile.
     * geolocate - особый случай, см. {@link #handleGeolocate}: не через FlectonePulse/консоль,
     * а напрямую через ip-api.com, результат сразу в чат, без похода в консоль.
     */
    private void handleLookup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sunshine.profile")) {
            sender.sendMessage(Component.text("У тебя нет прав на это.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text(
                    "Использование: /sunshine lookup <banlist|mutelist|warnlist|geolocate> <ник>", NamedTextColor.GRAY));
            return;
        }

        String actionArg = args[1].toLowerCase();
        String nickname = args[2];

        if (actionArg.equals("geolocate")) {
            handleGeolocate(sender, nickname);
            return;
        }

        String type = switch (actionArg) {
            case "banlist" -> "BAN";
            case "mutelist" -> "MUTE";
            case "warnlist" -> "WARN";
            default -> null;
        };
        if (type == null) {
            sender.sendMessage(Component.text("Неизвестное действие '" + actionArg + "' (banlist|mutelist|warnlist|geolocate).", NamedTextColor.RED));
            return;
        }

        handleTypedHistory(sender, nickname, type);
    }

    /** Список наказаний одного типа - читает fp_moderation напрямую, результат сразу в чат (не через консоль FlectonePulse). */
    private void handleTypedHistory(CommandSender sender, String nickname, String type) {
        java.util.UUID uuid = new ProfileService(plugin).resolveUuid(nickname);
        if (uuid == null) {
            sender.sendMessage(Component.text("Игрок '" + nickname + "' не найден.", NamedTextColor.YELLOW));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var punishments = plugin.flectonePulseHistory().punishmentsOfType(uuid, type, 10);
            Bukkit.getScheduler().runTask(plugin, () -> sendPunishmentHistory(sender, punishments));
        });
    }

    /** Геолокация напрямую через ip-api.com (в обход console-relay FlectonePulse, где текст нечитаем) - результат сразу в чат. */
    private void handleGeolocate(CommandSender sender, String nickname) {
        new ProfileService(plugin).resolveUuidAndIp(nickname, ip -> {
            if (ip == null) {
                sender.sendMessage(Component.text("IP игрока '" + nickname + "' неизвестен (нужен player-db).", NamedTextColor.YELLOW));
                return;
            }
            plugin.geoLocationService().lookup(ip).whenComplete((geo, throwable) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (throwable != null || geo == null) {
                            sender.sendMessage(Component.text("Не удалось определить геолокацию для " + nickname + " (" + ip + ").", NamedTextColor.RED));
                            return;
                        }
                        sender.sendMessage(Component.text(nickname + " (" + ip + "): ", NamedTextColor.GRAY)
                                .append(Component.text(geo.format(), NamedTextColor.WHITE)));
                    }));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("sunshine.admin");
        boolean profileAccess = sender.hasPermission("sunshine.profile");

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("status", "link", "unlink", "leave-community"));
            if (admin) {
                options.add("reload");
                options.add("db-sync");
                options.add("backup");
                options.add("admin-unlink");
                options.add("migrate-flectonepulse");
            }
            if (profileAccess) {
                options.add("profile");
                options.add("punish");
                options.add("lookup");
            }
            return options.stream().filter(o -> o.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("leave-community")) {
            return List.of("confirm").stream().filter(o -> o.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && admin && args[0].equalsIgnoreCase("admin-unlink")) {
            return onlinePlayerNames(args[1]);
        }
        if (args.length == 2 && profileAccess && args[0].equalsIgnoreCase("profile")) {
            return onlinePlayerNames(args[1]);
        }
        if (args.length == 2 && profileAccess && args[0].equalsIgnoreCase("punish")) {
            return List.of("ban", "mute", "kick", "warn").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && profileAccess && args[0].equalsIgnoreCase("punish")) {
            return onlinePlayerNames(args[2]);
        }
        if (args.length == 2 && profileAccess && args[0].equalsIgnoreCase("lookup")) {
            return List.of("banlist", "mutelist", "warnlist", "geolocate").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && profileAccess && args[0].equalsIgnoreCase("lookup")) {
            return onlinePlayerNames(args[2]);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames(String prefix) {
        String lower = prefix.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(lower))
                .toList();
    }
}
