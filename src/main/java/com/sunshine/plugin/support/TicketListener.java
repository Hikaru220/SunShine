package com.sunshine.plugin.support;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.TimeFormat;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;

public class TicketListener extends ListenerAdapter {

    private static final String BTN_OPEN = "sunshine:ticket:open";
    private static final String BTN_CLOSE = "sunshine:ticket:close";
    private static final String BTN_DELETE = "sunshine:ticket:delete";
    private static final String SELECT_THEME = "sunshine:ticket:theme";
    private static final String MODAL_PREFIX = "sunshine:ticket:modal:";
    private static final String TOPIC_PREFIX = "ticket-owner:";

    private static final int TRANSCRIPT_MAX_MESSAGES = 3000;
    private static final DateTimeFormatter TRANSCRIPT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String INPUT_NICKNAME = "nickname";
    private static final String INPUT_DESCRIPTION = "description";

    private final SunShinePlugin plugin;
    private final GuildInfra guildInfra;

    public TicketListener(SunShinePlugin plugin, GuildInfra guildInfra) {
        this.plugin = plugin;
        this.guildInfra = guildInfra;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("setup-tickets")) return;

        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("У тебя нет прав для этой команды.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Поддержка Sunshine")
                .setDescription("Нажмите кнопку ниже, чтобы отправить запрос в поддержку.")
                .setColor(new Color(0xff, 0xcb, 0x6b));

        event.getChannel().sendMessageEmbeds(embed.build())
                .addActionRow(Button.success(BTN_OPEN, "Отправить запрос").withEmoji(Emoji.fromUnicode("📨")))
                .queue();

        event.reply("Панель тикетов опубликована.").setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.equals(BTN_OPEN)) {
            handleOpen(event);
        } else if (id.equals(BTN_CLOSE)) {
            handleClose(event);
        } else if (id.equals(BTN_DELETE)) {
            handleDelete(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals(SELECT_THEME)) return;
        String themeId = event.getValues().get(0);
        event.replyModal(buildModal(themeId)).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith(MODAL_PREFIX)) return;
        String themeId = event.getModalId().substring(MODAL_PREFIX.length());
        handleModalSubmit(event, themeId);
    }

    private void handleOpen(ButtonInteractionEvent event) {
        StringSelectMenu menu = StringSelectMenu.create(SELECT_THEME)
                .setPlaceholder("Выберите тему обращения")
                .addOptions(TicketTheme.ALL.stream()
                        .map(t -> SelectOption.of(t.label(), t.id()).withEmoji(Emoji.fromUnicode(t.emoji())))
                        .toList())
                .build();

        com.sunshine.plugin.discord.InteractionLagLogger.timed(plugin, "ticket-open", event.getInteraction(),
                event.reply("Выберите тему обращения:")
                        .addActionRow(menu)
                        .setEphemeral(true));
    }

    private Modal buildModal(String themeId) {
        TextInput nickname = TextInput.create(INPUT_NICKNAME, "Ваш ник на сервере", TextInputStyle.SHORT)
                .setRequired(true)
                .setMaxLength(50)
                .build();
        TextInput description = TextInput.create(INPUT_DESCRIPTION, "Опишите вашу проблему подробно", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(1000)
                .build();

        return Modal.create(MODAL_PREFIX + themeId, "Создание тикета")
                .addActionRow(nickname)
                .addActionRow(description)
                .build();
    }

    private void handleModalSubmit(ModalInteractionEvent event, String themeId) {
        // Category/role resolution + channel creation can take longer than the 3s Discord allows
        // for the initial ack, so defer immediately and follow up via the interaction hook.
        event.deferReply(true).complete();
        InteractionHook hook = event.getHook();

        try {
            TicketTheme theme = TicketTheme.byId(themeId);
            if (theme == null) {
                hook.sendMessage("Неизвестная тема, попробуйте ещё раз.").queue();
                return;
            }

            String nickname = value(event, INPUT_NICKNAME);
            String description = value(event, INPUT_DESCRIPTION);
            Guild guild = event.getGuild();
            User user = event.getUser();
            Member selfMember = guild.getSelfMember();

            boolean media = "media".equals(theme.id());
            // заявка на медиа лежит в той же категории, что и заявки на сервер / набор в команду
            GuildInfra.Resolved infra = media
                    ? guildInfra.resolve(guild, "applications", "Заявки на сервер",
                            plugin.config().staffAppCategoryId(), "Recruiter", plugin.config().staffAppReviewerRoleIds())
                    : guildInfra.resolve(guild, "tickets", "Тикеты",
                            plugin.config().ticketsCategoryId(), "Support", plugin.config().ticketsSupportRoleIds());

            TextChannel existing = findOpen(infra.category(), user.getId());
            if (existing != null) {
                hook.sendMessage("У вас уже есть открытый тикет: " + existing.getAsMention()).queue();
                return;
            }

            String prefix = media ? "media-" : "ticket-";
            int number = nextNumber(infra.category(), prefix);
            String channelName = prefix + pad(number) + "-" + sanitize(user.getName());

            var action = guild.createTextChannel(channelName)
                    .setParent(infra.category())
                    .setTopic(TOPIC_PREFIX + user.getId())
                    .addPermissionOverride(guild.getPublicRole(), Collections.emptySet(), EnumSet.of(Permission.VIEW_CHANNEL))
                    .addPermissionOverride(event.getMember(),
                            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES),
                            Collections.emptySet())
                    .addPermissionOverride(selfMember,
                            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MANAGE_CHANNEL),
                            Collections.emptySet());

            for (Role role : infra.roles()) {
                action = action.addPermissionOverride(role,
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                                Permission.MESSAGE_ATTACH_FILES, Permission.MANAGE_CHANNEL),
                        Collections.emptySet());
            }

            TextChannel channel = action.complete();

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Тикет #" + pad(number) + " — " + theme.emoji() + " " + theme.label())
                    .addField("Ник на сервере", nickname, false)
                    .addField("Автор", user.getAsMention(), false)
                    .addField("Тема", theme.emoji() + " " + theme.label(), false)
                    .addField("Описание", description, false)
                    .setFooter("Скриншоты можно прикрепить сообщением ниже, если нужно")
                    .setColor(new Color(0xff, 0xcb, 0x6b));

            StringBuilder ping = new StringBuilder(user.getAsMention());
            for (Role role : infra.roles()) ping.append(' ').append(role.getAsMention());

            channel.sendMessage(ping.toString())
                    .setEmbeds(embed.build())
                    .addActionRow(Button.danger(BTN_CLOSE, "Закрыть тикет").withEmoji(Emoji.fromUnicode("🔒")))
                    .queue();

            hook.sendMessage("Тикет создан: " + channel.getAsMention()).queue();
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось создать тикет: " + e.getMessage());
            hook.sendMessage("Произошла ошибка, попробуйте ещё раз.").queue();
        }
    }

    private void handleClose(ButtonInteractionEvent event) {
        TextChannel channel = event.getChannel().asTextChannel();
        String topic = channel.getTopic();
        String ownerId = (topic != null && topic.startsWith(TOPIC_PREFIX)) ? topic.substring(TOPIC_PREFIX.length()) : null;

        Member member = event.getMember();
        GuildInfra.Resolved infra = guildInfra.resolve(event.getGuild(), "tickets", "Тикеты",
                plugin.config().ticketsCategoryId(), "Support", plugin.config().ticketsSupportRoleIds());

        boolean isOwner = ownerId != null && ownerId.equals(event.getUser().getId());
        boolean isSupport = member != null && (infra.roles().stream().anyMatch(r -> member.getRoles().contains(r)) || isRecruiter(event.getGuild(), member));
        boolean canManage = member != null && member.hasPermission(Permission.MANAGE_CHANNEL);

        if (!isOwner && !isSupport && !canManage) {
            event.reply("У вас нет прав закрыть этот тикет.").setEphemeral(true).queue();
            return;
        }
        if (channel.getName().startsWith("closed-")) {
            event.reply("Тикет уже закрыт.").setEphemeral(true).queue();
            return;
        }

        if (ownerId != null) {
            try {
                Member ownerMember = event.getGuild().retrieveMemberById(ownerId).complete();
                channel.upsertPermissionOverride(ownerMember).deny(Permission.MESSAGE_SEND).queue(null, t -> { });
            } catch (Exception ignored) {
                // owner left the guild - nothing to lock down
            }
        }

        String originalName = channel.getName();
        String newName = "closed-" + originalName;
        if (newName.length() > 100) newName = newName.substring(0, 100);
        channel.getManager().setName(newName).queue(null, t -> { });

        EmbedBuilder embed = new EmbedBuilder()
                .setDescription("🔒 Тикет закрыт пользователем " + event.getUser().getAsMention())
                .setColor(new Color(0xff, 0x55, 0x55));

        event.replyEmbeds(embed.build())
                .addActionRow(Button.danger(BTN_DELETE, "Удалить тикет").withEmoji(Emoji.fromUnicode("🗑")))
                .queue();

        String closerRole = isOwner ? "автор тикета" : isSupport ? "поддержка" : "модератор канала";
        logClosed(event, channel, originalName, ownerId, closerRole);
    }

    /** Лог закрытия тикета в support.tickets.log-channel-id. Имя канала пишем текстом, а не упоминанием - после удаления тикета упоминание превратится в "#удалённый-канал". */
    private void logClosed(ButtonInteractionEvent event, TextChannel ticketChannel, String ticketName, String ownerId, String closerRole) {
        String logChannelId = plugin.config().ticketsLogChannelId();
        if (logChannelId.isBlank()) return;
        GuildMessageChannel logChannel = event.getGuild().getChannelById(GuildMessageChannel.class, logChannelId);
        if (logChannel == null) {
            GuildChannel any = event.getJDA().getGuildChannelById(logChannelId);
            String reason;
            if (any == null) {
                reason = "support-бот не видит канал с таким ID ни на одном сервере (неверный ID, или у бота нет права \"Просмотр канала\")";
            } else if (!any.getGuild().equals(event.getGuild())) {
                reason = "канал находится на другом сервере (" + any.getGuild().getName() + "), а тикет закрыт на этом";
            } else {
                reason = "канал типа " + any.getType() + " - в него нельзя писать сообщения (нужен текстовый канал)";
            }
            plugin.getLogger().warning("Тикеты: лог закрытия не отправлен, канал support.tickets.log-channel-id=" + logChannelId + ": " + reason + ".");
            return;
        }

        EmbedBuilder log = new EmbedBuilder()
                .setTitle("🔒 Тикет закрыт")
                .setColor(new Color(0xff, 0x55, 0x55))
                .addField("Тикет", "`" + ticketName + "`", true)
                .addField("Автор", ownerId == null ? "неизвестно" : "<@" + ownerId + ">", true)
                .addField("Закрыл", event.getUser().getAsMention() + " (" + closerRole + ")", true)
                .addField("Открыт", TimeFormat.DATE_TIME_SHORT.format(ticketChannel.getTimeCreated()), true)
                .setTimestamp(Instant.now());

        java.util.function.Consumer<Throwable> onSendFailed =
                t -> plugin.getLogger().warning("Тикеты: не удалось отправить лог закрытия в канал " + logChannelId + ": " + t.getMessage());

        // Вся переписка тикета файлом: канал потом удаляют, а вместе с ним пропадает и история.
        ticketChannel.getIterableHistory().takeAsync(TRANSCRIPT_MAX_MESSAGES).whenComplete((messages, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Тикеты: не удалось прочитать историю " + ticketName + " для лога: " + error.getMessage());
                logChannel.sendMessageEmbeds(log.build()).queue(null, onSendFailed);
                return;
            }
            String transcript = buildTranscript(ticketName, ownerId, event.getUser(), closerRole, ticketChannel,
                    messages, messages.size() >= TRANSCRIPT_MAX_MESSAGES);
            logChannel.sendMessageEmbeds(log.build())
                    .addFiles(FileUpload.fromData(transcript.getBytes(StandardCharsets.UTF_8), ticketName + ".txt"))
                    .queue(null, onSendFailed);
        });
    }

    /** Текстовая расшифровка тикета: шапка, затем все сообщения от старых к новым (вложения - ссылками, embed - текстом). Время в UTC. */
    private String buildTranscript(String ticketName, String ownerId, User closer, String closerRole,
                                   TextChannel channel, List<Message> newestFirst, boolean truncated) {
        List<Message> messages = new ArrayList<>(newestFirst);
        Collections.reverse(messages);

        String ownerLabel = ownerId == null ? "неизвестно" : ownerId;
        for (Message m : messages) {
            if (m.getAuthor().getId().equals(ownerId)) {
                ownerLabel = m.getAuthor().getName() + " (" + ownerId + ")";
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Тикет: ").append(ticketName).append('\n');
        sb.append("Автор: ").append(ownerLabel).append('\n');
        sb.append("Открыт: ").append(TRANSCRIPT_TIME.format(channel.getTimeCreated())).append(" UTC\n");
        sb.append("Закрыт: ").append(TRANSCRIPT_TIME.format(Instant.now())).append(" UTC, закрыл ")
                .append(closer.getName()).append(" (").append(closer.getId()).append(", ").append(closerRole).append(")\n");
        sb.append("Сообщений: ").append(messages.size());
        if (truncated) sb.append(" (достигнут лимит ").append(TRANSCRIPT_MAX_MESSAGES).append(", самые старые не вошли)");
        sb.append("\n").append("=".repeat(60)).append("\n\n");

        for (Message m : messages) {
            User author = m.getAuthor();
            sb.append('[').append(TRANSCRIPT_TIME.format(m.getTimeCreated())).append("] ")
                    .append(author.getName()).append(" (").append(author.getId()).append(author.isBot() ? ", бот" : "").append(')');
            if (m.isEdited()) sb.append(" [изменено]");
            sb.append(": ");
            String content = m.getContentDisplay();
            if (!content.isBlank()) sb.append(content.replace("\n", "\n    "));
            sb.append('\n');
            for (Message.Attachment attachment : m.getAttachments()) {
                sb.append("    [вложение] ").append(attachment.getFileName()).append(" - ").append(attachment.getUrl()).append('\n');
            }
            for (MessageEmbed embed : m.getEmbeds()) {
                if (embed.getTitle() != null) sb.append("    [embed] ").append(embed.getTitle()).append('\n');
                if (embed.getDescription() != null) sb.append("    ").append(embed.getDescription().replace("\n", "\n    ")).append('\n');
                for (MessageEmbed.Field field : embed.getFields()) {
                    sb.append("    ").append(field.getName()).append(": ")
                            .append(field.getValue() == null ? "" : field.getValue().replace("\n", "\n        ")).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void handleDelete(ButtonInteractionEvent event) {
        Member member = event.getMember();
        GuildInfra.Resolved infra = guildInfra.resolve(event.getGuild(), "tickets", "Тикеты",
                plugin.config().ticketsCategoryId(), "Support", plugin.config().ticketsSupportRoleIds());

        boolean isSupport = member != null && (infra.roles().stream().anyMatch(r -> member.getRoles().contains(r)) || isRecruiter(event.getGuild(), member));
        boolean canManage = member != null && member.hasPermission(Permission.MANAGE_CHANNEL);

        if (!isSupport && !canManage) {
            event.reply("Удалять тикеты может только поддержка.").setEphemeral(true).queue();
            return;
        }

        event.reply("Тикет будет удалён через 5 секунд...").queue();
        TextChannel channel = event.getChannel().asTextChannel();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> channel.delete().queue(null, t -> { }), 100L);
    }

    private boolean isRecruiter(Guild guild, Member member) {
        GuildInfra.Resolved apps = guildInfra.resolve(guild, "applications", "Заявки на сервер",
                plugin.config().staffAppCategoryId(), "Recruiter", plugin.config().staffAppReviewerRoleIds());
        return apps.roles().stream().anyMatch(r -> member.getRoles().contains(r));
    }

    private TextChannel findOpen(Category category, String userId) {
        String topic = TOPIC_PREFIX + userId;
        for (TextChannel channel : category.getTextChannels()) {
            if (topic.equals(channel.getTopic()) && !channel.getName().startsWith("closed-")) {
                return channel;
            }
        }
        return null;
    }

    private int nextNumber(Category category, String namePrefix) {
        return (int) category.getTextChannels().stream().filter(c -> c.getName().startsWith(namePrefix)).count() + 1;
    }

    private String pad(int number) {
        return String.format("%04d", number);
    }

    private String sanitize(String username) {
        String cleaned = username.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9а-яё]+", "-");
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }

    private String value(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString();
    }
}
