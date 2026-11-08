package com.sunshine.plugin.application;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.awt.Color;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Заявки на сервер. После анкеты бот создаёт приватный канал заявки: модератор видит ответы (с баннером
 * снизу) и кнопки Принять / Отказать / корзина, заявителю в канале задаются дополнительные вопросы.
 * Решение публикуется в канале сообщением от вебхука, при одобрении снимается роль "new". Если каналы
 * заявок выключены или создать канал не вышло - старый режим: карточка в channels.applications-review и ЛС.
 */
public class ApplicationListener extends ListenerAdapter {

    private static final String BTN_APPLY = "sunshine:apply:start";
    private static final String MODAL_APPLY = "sunshine:apply:modal";
    private static final String BTN_ACCEPT_PREFIX = "sunshine:app:accept:";
    private static final String BTN_DECLINE_PREFIX = "sunshine:app:decline:";
    private static final String BTN_DELETE_PREFIX = "sunshine:app:delete:";
    private static final String BTN_REOPEN_PREFIX = "sunshine:app:reopen:";
    private static final String MODAL_DECLINE_PREFIX = "sunshine:app:declinereason:";

    private static final String INPUT_NICKNAME = "nickname";
    private static final String INPUT_AGE = "age";
    private static final String INPUT_SOURCE = "source";
    private static final String INPUT_PLANS = "plans";
    private static final String INPUT_ABOUT = "about";
    private static final String INPUT_DECLINE_REASON = "reason";

    private final SunShinePlugin plugin;
    private final ApplicationWebhook webhook;

    public ApplicationListener(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.webhook = new ApplicationWebhook(plugin);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("apply-panel")) return;

        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("У тебя нет прав для этой команды.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(plugin.config().applicationsPanelTitle())
                .setDescription(plugin.config().applicationsPanelDescription())
                .setColor(new Color(87, 242, 135));

        event.replyEmbeds(embed.build())
                .addActionRow(Button.success(BTN_APPLY, plugin.config().applicationsButtonLabel()))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();

        if (id.equals(BTN_APPLY)) {
            handleApplyStart(event);
        } else if (id.startsWith(BTN_ACCEPT_PREFIX)) {
            handleAccept(event, id.substring(BTN_ACCEPT_PREFIX.length()));
        } else if (id.startsWith(BTN_DECLINE_PREFIX)) {
            handleDeclineStart(event, id.substring(BTN_DECLINE_PREFIX.length()));
        } else if (id.startsWith(BTN_DELETE_PREFIX)) {
            handleDelete(event);
        } else if (id.startsWith(BTN_REOPEN_PREFIX)) {
            handleReopen(event, id.substring(BTN_REOPEN_PREFIX.length()));
        }
    }

    /** Модератор заявок: право "Управление сервером" или одна из applications.rooms.reviewer-role-ids. */
    private boolean isReviewer(Member member) {
        if (member == null) return false;
        if (member.hasPermission(Permission.MANAGE_SERVER)) return true;
        List<String> roleIds = plugin.config().applicationsReviewerRoleIds();
        return member.getRoles().stream().anyMatch(r -> roleIds.contains(r.getId()));
    }

    // ==================== Подача заявки ====================

    private void handleApplyStart(ButtonInteractionEvent event) {
        String discordId = event.getUser().getId();
        Application existing = plugin.applicationManager().get(discordId);

        if (existing != null && existing.status() == ApplicationStatus.ACCEPTED) {
            event.reply("Ваша заявка уже была принята, повторно подавать её нельзя.").setEphemeral(true).queue();
            return;
        }
        if (existing != null && existing.status() == ApplicationStatus.PENDING) {
            event.reply("Ваша заявка уже на рассмотрении.").setEphemeral(true).queue();
            return;
        }

        // label у TextInput - не больше 45 символов (иначе JDA кидает исключение до ответа и Discord пишет "не ответило")
        Modal modal = Modal.create(MODAL_APPLY, "Заявка")
                .addActionRow(TextInput.create(INPUT_NICKNAME, "Ваш никнейм:", TextInputStyle.SHORT)
                        .setRequired(true).setMaxLength(16).build())
                .addActionRow(TextInput.create(INPUT_AGE, "Сколько вам лет?", TextInputStyle.SHORT)
                        .setRequired(true).setMaxLength(3).build())
                .addActionRow(TextInput.create(INPUT_SOURCE, "Откуда вы узнали о проекте?", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(300).build())
                .addActionRow(TextInput.create(INPUT_PLANS, "Чем вы планируете заниматься на сервере?", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(500).build())
                .addActionRow(TextInput.create(INPUT_ABOUT, "Расскажите о себе:", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(500).build())
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith(MODAL_DECLINE_PREFIX)) {
            handleDeclineSubmit(event);
            return;
        }
        if (!event.getModalId().equals(MODAL_APPLY)) return;

        String nickname = value(event, INPUT_NICKNAME).trim();
        String age = value(event, INPUT_AGE).trim();
        String source = value(event, INPUT_SOURCE).trim();
        String plans = value(event, INPUT_PLANS).trim();
        String about = value(event, INPUT_ABOUT).trim();

        if (!nickname.matches("[A-Za-z0-9_]{2,16}")) {
            event.reply("Игровой ник должен состоять из 2-16 латинских букв, цифр и `_` (как настоящий никнейм Minecraft). Попробуй ещё раз.")
                    .setEphemeral(true).queue();
            return;
        }
        String discordId = event.getUser().getId();
        String discordTag = event.getUser().getName();

        Application previous = plugin.applicationManager().get(discordId);
        String previousChannelId = previous == null ? null : previous.channelId();
        Application application = plugin.applicationManager().submit(discordId, discordTag, nickname, age, source, plans, about);

        Guild guild = event.getGuild();
        if (plugin.config().applicationsRoomsEnabled() && guild != null) {
            event.deferReply(true).queue();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> openRoom(guild, application, previousChannelId, event));
            return;
        }

        postToReviewChannel(application);
        event.reply("Заявка отправлена! Ожидай решения администрации.").setEphemeral(true).queue();
    }

    private String value(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString();
    }

    /** Создаёт приватный канал заявки: карточка с анкетой и баннером, затем приветствие и вопросы заявителю. */
    private void openRoom(Guild guild, Application application, String previousChannelId, ModalInteractionEvent event) {
        try {
            if (previousChannelId != null) {
                TextChannel old = guild.getTextChannelById(previousChannelId);
                if (old != null) old.delete().queue(null, t -> { });
            }

            Category category = resolveCategory(guild);
            EnumSet<Permission> allow = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                    Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);
            var action = guild.createTextChannel("zayavka-" + application.minecraftName().toLowerCase().replace('_', '-'))
                    .setParent(category)
                    .setTopic("application:" + application.discordId())
                    .addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.VIEW_CHANNEL))
                    .addPermissionOverride(guild.getSelfMember(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                            Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MANAGE_CHANNEL,
                            Permission.MANAGE_WEBHOOKS), EnumSet.noneOf(Permission.class));
            for (String roleId : plugin.config().applicationsReviewerRoleIds()) {
                var role = roleId.isBlank() ? null : guild.getRoleById(roleId);
                if (role != null) action = action.addPermissionOverride(role, allow, EnumSet.noneOf(Permission.class));
            }
            try {
                Member applicant = guild.retrieveMemberById(application.discordId()).complete();
                action = action.addPermissionOverride(applicant, allow, EnumSet.noneOf(Permission.class));
            } catch (Exception ignored) {
                // заявитель вышел с сервера - канал всё равно создаём, ответы он не увидит
            }
            TextChannel channel = action.complete();
            plugin.applicationManager().setChannelId(application.discordId(), channel.getId());

            // 1) анкета для модератора + баннер снизу
            Banner banner = loadBanner();
            EmbedBuilder card = buildApplicationEmbed(application);
            if (banner != null) card.setImage("attachment://" + banner.fileName());
            var cardAction = channel.sendMessageEmbeds(card.build());
            if (banner != null) cardAction = cardAction.addFiles(FileUpload.fromData(banner.bytes(), banner.fileName()));
            if (plugin.config().applicationsPingReviewers()) {
                String pings = reviewerPings();
                if (!pings.isBlank()) cardAction = cardAction.setContent(pings);
            }
            cardAction.addComponents(pendingRow(application.discordId())).complete();

            // 2) приветствие, вопросы и подсказка про покупку проходки
            channel.sendMessage(buildQuestionsMessage(application.discordId())).complete();

            event.getHook().editOriginal("✅ Заявка отправлена! Перейди в канал " + channel.getAsMention()
                    + " и ответь на вопросы.").queue();
        } catch (Exception e) {
            plugin.getLogger().warning("Заявки: не удалось создать канал заявки (" + e.getMessage()
                    + ") - отправляю карточку в старом режиме. Нужны права бота 'Manage Channels' и 'Manage Webhooks'.");
            plugin.applicationManager().setChannelId(application.discordId(), null);
            postToReviewChannel(application);
            event.getHook().editOriginal("Заявка отправлена! Ожидай решения администрации.").queue();
        }
    }

    private Category resolveCategory(Guild guild) {
        String id = plugin.config().applicationsRoomsCategoryId();
        if (!id.isBlank()) {
            Category byId = guild.getCategoryById(id.trim());
            if (byId != null) return byId;
        }
        List<Category> byName = guild.getCategoriesByName("Заявки на сервер", true);
        if (!byName.isEmpty()) return byName.get(0);
        return guild.createCategory("Заявки на сервер").complete();
    }

    private String reviewerPings() {
        StringBuilder sb = new StringBuilder();
        for (String id : plugin.config().applicationsReviewerRoleIds()) {
            if (!id.isBlank()) sb.append("<@&").append(id).append("> ");
        }
        return sb.toString().trim();
    }

    private Button deleteButton(String discordId) {
        return Button.secondary(BTN_DELETE_PREFIX + discordId, Emoji.fromUnicode("🗑"));
    }

    /** Кнопки заявки на рассмотрении: Принять / Отказать / корзина. */
    private ActionRow pendingRow(String discordId) {
        return ActionRow.of(
                Button.success(BTN_ACCEPT_PREFIX + discordId, "Принять"),
                Button.danger(BTN_DECLINE_PREFIX + discordId, "Отказать"),
                deleteButton(discordId));
    }

    /** Кнопки после решения: рассмотреть ещё раз / корзина. */
    private ActionRow decidedRow(String discordId) {
        return ActionRow.of(
                Button.primary(BTN_REOPEN_PREFIX + discordId, "Рассмотреть заново").withEmoji(Emoji.fromUnicode("🔄")),
                deleteButton(discordId));
    }

    private String buildQuestionsMessage(String discordId) {
        StringBuilder sb = new StringBuilder("<@").append(discordId).append(">\n");
        sb.append(plugin.config().applicationsGreeting()).append("\n\n");
        int number = 1;
        for (String question : plugin.config().applicationsQuestions()) {
            sb.append("- ").append(question).append('\n');
        }
        String trailer = plugin.config().applicationsTrailerNote();
        if (!trailer.isBlank()) {
            sb.append('\n').append(trailer).append('\n');
        }
        sb.append('\n').append(plugin.config().applicationsShopNote());
        String text = sb.toString();
        return text.length() <= 2000 ? text : text.substring(0, 1997) + "...";
    }

    private record Banner(byte[] bytes, String fileName) {
    }

    /** Баннер под анкетой: свой файл из applications.rooms.banner-file или встроенный в плагин. null - без картинки. */
    private Banner loadBanner() {
        try {
            String path = plugin.config().applicationsBannerFile();
            if (!path.isBlank()) {
                File file = new File(plugin.getDataFolder(), path);
                if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".png";
                    return new Banner(Files.readAllBytes(file.toPath()), "application-banner" + extension);
                }
                plugin.getLogger().warning("Заявки: applications.rooms.banner-file='" + path + "' не найден в папке плагина, беру встроенный баннер.");
            }
            try (InputStream in = plugin.getResource("application/banner.jpg")) {
                return in == null ? null : new Banner(in.readAllBytes(), "application-banner.jpg");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Заявки: не удалось загрузить баннер: " + e.getMessage());
            return null;
        }
    }

    // ==================== Старый режим ====================

    private void postToReviewChannel(Application application) {
        String reviewChannelId = plugin.config().applicationsReviewChannelId();
        MessageChannel reviewChannel = reviewChannelId.isBlank() ? null
                : plugin.discordBot().jda().getTextChannelById(reviewChannelId);
        if (reviewChannel == null) return;
        reviewChannel.sendMessageEmbeds(buildApplicationEmbed(application).build())
                .addActionRow(
                        Button.success(BTN_ACCEPT_PREFIX + application.discordId(), "Принять"),
                        Button.danger(BTN_DECLINE_PREFIX + application.discordId(), "Отклонить"))
                .queue(null, throwable -> { });
    }

    private EmbedBuilder buildApplicationEmbed(Application application) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Заявка от: " + application.minecraftName() + " (@" + application.discordTag() + ")")
                .addField("Игровой ник", cut(application.minecraftName(), 1000), false)
                .addField("Сколько лет", cut(application.age(), 1000), false)
                .addField("Откуда узнал о проекте", cut(application.source(), 1000), false);
        if (!application.plans().isBlank()) {
            embed.addField("Чем планирует заниматься на сервере", cut(application.plans(), 1000), false);
        }
        embed.addField("О себе", cut(application.about(), 1000), false);
        if (!application.rulesAccepted().isBlank()) {
            embed.addField("Ознакомлены с правилами", cut(application.rulesAccepted(), 1000), false);
        }
        return embed.setFooter("Discord: " + application.discordTag() + " | " + application.discordId())
                .setColor(new Color(88, 101, 242));
    }

    private static String cut(String text, int max) {
        if (text == null || text.isBlank()) return "-";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    // ==================== Решение ====================

    private void handleAccept(ButtonInteractionEvent event, String discordId) {
        if (!isReviewer(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }

        Application application = plugin.applicationManager().get(discordId);
        if (application == null) {
            event.reply("Заявка не найдена.").setEphemeral(true).queue();
            return;
        }
        if (application.status() != ApplicationStatus.PENDING) {
            event.reply("Эта заявка уже обработана.").setEphemeral(true).queue();
            return;
        }

        plugin.applicationManager().decide(discordId, ApplicationStatus.ACCEPTED, event.getUser().getAsTag());
        boolean room = application.channelId() != null && application.channelId().equals(event.getChannel().getId());

        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        EmbedBuilder edited = embeds.isEmpty() ? buildApplicationEmbed(application) : new EmbedBuilder(embeds.get(0));
        edited.addField("Решение", "✅ Принята администратором " + event.getUser().getAsMention(), false);
        edited.setColor(new Color(87, 242, 135));

        event.editMessageEmbeds(edited.build())
                .setComponents(room ? List.of(decidedRow(discordId)) : List.<ActionRow>of())
                .queue(null, throwable -> { });

        if (plugin.config().applicationsAutoWhitelist()) {
            String nickname = application.minecraftName();
            String commandTemplate = plugin.config().applicationsWhitelistCommand();

            if (commandTemplate == null || commandTemplate.isBlank()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nickname);
                    // setWhitelisted() fires a WhitelistStateUpdateEvent, which Paper requires to run
                    // on the main thread - only the (possibly blocking) name lookup above is async.
                    Bukkit.getScheduler().runTask(plugin, () -> offlinePlayer.setWhitelisted(true));
                });
            } else {
                // nickname was already validated against ^[A-Za-z0-9_]{2,16}$ on submission,
                // so it's safe to splice into a console command here.
                String command = commandTemplate.replace("{player}", nickname);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
        }

        Guild guild = event.getGuild();
        giveAcceptedRole(guild, discordId);
        removeRoleOnAccept(guild, discordId);

        if (room) {
            String text = "<@" + discordId + ">\n"
                    + plugin.config().applicationsApprovedText().replace("{ip}", plugin.config().applicationsServerIp());
            webhook.send(event.getChannel().asTextChannel(), text, discordId);
        } else {
            notifyAccepted(discordId, application.minecraftName());
        }
    }


    /**
     * Покупка проходки на сайте: вызывается консольной командой /sunshine pass <ник>.
     * Добавляет ник в белый список, принимает заявку (если есть, ожидающая), выдаёт роль в Discord,
     * снимает роль "new" и пишет об этом в канал заявки / в ЛС. Возвращает текст для консоли.
     */
    public String approveByPurchase(String nickname) {
        Application application = plugin.applicationManager().findLatestByNickname(nickname);
        String discordId = application != null ? application.discordId() : null;
        if (discordId == null) {
            java.util.UUID uuid = plugin.linkManager().uuidForName(nickname);
            if (uuid != null) {
                discordId = plugin.linkManager().discordIdFor(uuid);
            }
        }

        if (plugin.config().applicationsAutoWhitelist()) {
            String commandTemplate = plugin.config().applicationsWhitelistCommand();
            if (commandTemplate == null || commandTemplate.isBlank()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nickname);
                    Bukkit.getScheduler().runTask(plugin, () -> offlinePlayer.setWhitelisted(true));
                });
            } else {
                String command = commandTemplate.replace("{player}", nickname);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
        }
        if (discordId == null) {
            return "ник " + nickname + " добавлен в белый список; Discord не найден (нет заявки и привязки), роль не выдана";
        }

        if (application != null && application.status() != ApplicationStatus.ACCEPTED) {
            plugin.applicationManager().decide(discordId, ApplicationStatus.ACCEPTED, "оплата проходки на сайте");
        }
        var jda = plugin.discordBot().jda();
        Guild guild = jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
        giveAcceptedRole(guild, discordId);
        removeRoleOnAccept(guild, discordId);

        String text = "<@" + discordId + ">\n✅ Проходка оплачена на сайте — заявка принята автоматически.\n"
                + plugin.config().applicationsApprovedText().replace("{ip}", plugin.config().applicationsServerIp());
        var channel = application != null && application.channelId() != null
                ? jda.getTextChannelById(application.channelId()) : null;
        if (channel != null) {
            webhook.send(channel, text, discordId);
        } else {
            notifyAccepted(discordId, nickname);
        }
        return "ник " + nickname + ": белый список, роль в Discord выдана, игрок уведомлён";
    }

    private void notifyAccepted(String discordId, String minecraftName) {
        plugin.discordBot().jda().retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        dm -> dm.sendMessage("✅ Твоя заявка на SunShine принята! Заходи на сервер под ником `"
                                        + minecraftName + "`.")
                                .queue(null, throwable -> { }),
                        throwable -> { }
                ),
                throwable -> { }
        );
    }

    private void handleDeclineStart(ButtonInteractionEvent event, String discordId) {
        if (!isReviewer(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }

        Application application = plugin.applicationManager().get(discordId);
        if (application == null) {
            event.reply("Заявка не найдена.").setEphemeral(true).queue();
            return;
        }
        if (application.status() != ApplicationStatus.PENDING) {
            event.reply("Эта заявка уже обработана.").setEphemeral(true).queue();
            return;
        }

        TextInput reason = TextInput.create(INPUT_DECLINE_REASON, "Причина отклонения", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(500)
                .build();

        Modal modal = Modal.create(MODAL_DECLINE_PREFIX + discordId + ":" + event.getChannel().getId() + ":" + event.getMessageId(),
                        "Отклонить заявку")
                .addActionRow(reason)
                .build();

        event.replyModal(modal).queue();
    }

    private void handleDeclineSubmit(ModalInteractionEvent event) {
        if (!isReviewer(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }

        String[] parts = event.getModalId().substring(MODAL_DECLINE_PREFIX.length()).split(":");
        if (parts.length != 3) return;
        String discordId = parts[0];
        String channelId = parts[1];
        String messageId = parts[2];

        Application application = plugin.applicationManager().get(discordId);
        if (application == null) {
            event.reply("Заявка не найдена.").setEphemeral(true).queue();
            return;
        }
        if (application.status() != ApplicationStatus.PENDING) {
            event.reply("Эта заявка уже обработана.").setEphemeral(true).queue();
            return;
        }

        String reason = value(event, INPUT_DECLINE_REASON);

        plugin.applicationManager().decide(discordId, ApplicationStatus.DECLINED, event.getUser().getAsTag());
        plugin.applicationManager().setDeclineReason(discordId, reason);
        boolean room = application.channelId() != null && application.channelId().equals(channelId);

        TextChannel channel = plugin.discordBot().jda().getTextChannelById(channelId);
        if (channel != null) {
            channel.retrieveMessageById(messageId).queue(message -> {
                EmbedBuilder edited = message.getEmbeds().isEmpty()
                        ? buildApplicationEmbed(application) : new EmbedBuilder(message.getEmbeds().get(0));
                edited.addField("Решение", "❌ Отклонена администратором " + event.getUser().getAsMention()
                        + "\nПричина: " + reason, false);
                edited.setColor(new Color(237, 66, 69));
                message.editMessageEmbeds(edited.build())
                        .setComponents(room ? List.of(decidedRow(discordId)) : List.<ActionRow>of())
                        .queue(null, throwable -> { });
            }, throwable -> { });
        }

        event.reply("Заявка отклонена, причина отправлена заявителю.").setEphemeral(true).queue();

        if (room && channel != null) {
            String text = "<@" + discordId + ">\n" + plugin.config().applicationsDeclinedText()
                    + "\n**Причина:** " + reason;
            webhook.send(channel, text, discordId);
        } else {
            plugin.discordBot().jda().retrieveUserById(discordId).queue(
                    user -> user.openPrivateChannel().queue(
                            dm -> dm.sendMessage("❌ Твоя заявка на SunShine отклонена.\nПричина: " + reason)
                                    .queue(null, throwable -> { }),
                            throwable -> { }
                    ),
                    throwable -> { }
            );
        }
    }

    /** Корзина: удаляет канал заявки (через 5 секунд, чтобы модератор видел подтверждение). */
    private void handleDelete(ButtonInteractionEvent event) {
        if (!isReviewer(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }
        event.reply("Канал заявки будет удалён через 5 секунд...").queue();
        event.getChannel().asTextChannel().delete().queueAfter(5, TimeUnit.SECONDS, null, throwable -> { });
    }

    /**
     * "Рассмотреть заново": возвращает заявку в ожидание и кнопки Принять/Отказать. Если заявку уже
     * одобряли - откатывает последствия: убирает из белого списка, снимает роль игрока и возвращает
     * роль "new". Решение (принять/отказать) модератор принимает заново.
     */
    private void handleReopen(ButtonInteractionEvent event, String discordId) {
        if (!isReviewer(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }
        Application application = plugin.applicationManager().get(discordId);
        if (application == null) {
            event.reply("Заявка не найдена.").setEphemeral(true).queue();
            return;
        }
        if (application.status() == ApplicationStatus.PENDING) {
            event.reply("Заявка уже на рассмотрении.").setEphemeral(true).queue();
            return;
        }

        boolean wasAccepted = application.status() == ApplicationStatus.ACCEPTED;
        plugin.applicationManager().decide(discordId, ApplicationStatus.PENDING, null);
        plugin.applicationManager().setDeclineReason(discordId, null);
        if (wasAccepted) revertAcceptance(event.getGuild(), application);

        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        EmbedBuilder edited = embeds.isEmpty() ? buildApplicationEmbed(application) : new EmbedBuilder(embeds.get(0));
        edited.getFields().removeIf(field -> "Решение".equals(field.getName()));
        edited.setColor(new Color(88, 101, 242));

        event.editMessageEmbeds(edited.build()).setComponents(pendingRow(discordId)).queue(null, throwable -> { });
        event.getChannel().sendMessage("🔄 Заявка возвращена на рассмотрение: " + event.getUser().getAsMention()
                + (wasAccepted ? "\n(игрок убран из белого списка, роли возвращены)" : "")).queue(null, throwable -> { });
    }

    private void revertAcceptance(Guild guild, Application application) {
        String nickname = application.minecraftName();
        String command = plugin.config().applicationsWhitelistRemoveCommand();
        if (command.isBlank()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                @SuppressWarnings("deprecation")
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nickname);
                Bukkit.getScheduler().runTask(plugin, () -> offlinePlayer.setWhitelisted(false));
            });
        } else {
            String resolved = command.replace("{player}", nickname);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        }

        if (guild == null) return;
        String acceptedRoleId = plugin.config().applicationsAcceptedRoleId();
        String removedRoleId = plugin.config().applicationsRemoveRoleId();
        guild.retrieveMemberById(application.discordId()).queue(member -> {
            var accepted = acceptedRoleId.isBlank() ? null : guild.getRoleById(acceptedRoleId);
            if (accepted != null) guild.removeRoleFromMember(member, accepted).queue(null, t -> { });
            var restored = removedRoleId.isBlank() ? null : guild.getRoleById(removedRoleId);
            if (restored != null) guild.addRoleToMember(member, restored).queue(null, t -> { });
        }, throwable -> { });
    }

    private void giveAcceptedRole(Guild guild, String discordId) {
        String roleId = plugin.config().applicationsAcceptedRoleId();
        if (roleId.isBlank() || guild == null) return;

        var role = guild.getRoleById(roleId);
        if (role == null) {
            plugin.getLogger().warning("applications.accepted-role-id указывает на несуществующую роль: " + roleId);
            return;
        }

        guild.retrieveMemberById(discordId).queue(
                member -> guild.addRoleToMember(member, role).queue(null, throwable -> { }),
                throwable -> { } // applicant left the server, nothing to do
        );
    }

    /** При одобрении снимаем роль "new" (applications.rooms.remove-role-id). */
    private void removeRoleOnAccept(Guild guild, String discordId) {
        String roleId = plugin.config().applicationsRemoveRoleId();
        if (roleId.isBlank() || guild == null) return;

        var role = guild.getRoleById(roleId);
        if (role == null) {
            plugin.getLogger().warning("applications.rooms.remove-role-id указывает на несуществующую роль: " + roleId);
            return;
        }

        guild.retrieveMemberById(discordId).queue(
                member -> guild.removeRoleFromMember(member, role).queue(null, throwable ->
                        plugin.getLogger().warning("Заявки: не удалось снять роль " + roleId + ": " + throwable.getMessage())),
                throwable -> { }
        );
    }
}
