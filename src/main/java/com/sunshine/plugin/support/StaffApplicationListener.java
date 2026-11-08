package com.sunshine.plugin.support;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class StaffApplicationListener extends ListenerAdapter {

    private static final String BTN_OPEN = "sunshine:staffapp:open";
    private static final String BTN_CLOSE = "sunshine:staffapp:close";
    private static final String BTN_DELETE = "sunshine:staffapp:delete";
    private static final String MODAL_ID = "sunshine:staffapp:modal";
    private static final String TOPIC_PREFIX = "application-owner:";

    private final SunShinePlugin plugin;
    private final GuildInfra guildInfra;

    public StaffApplicationListener(SunShinePlugin plugin, GuildInfra guildInfra) {
        this.plugin = plugin;
        this.guildInfra = guildInfra;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("setup-applications")) return;

        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("У тебя нет прав для этой команды.").setEphemeral(true).queue();
            return;
        }

        String description = "Нажмите кнопку ниже, чтобы подать заявку на вступление в команду. "
                + "Заполните анкету — мы откроем для вас приватный канал и рассмотрим её.";

        String requiredRoleId = plugin.config().staffAppRequiredRoleId();
        if (requiredRoleId != null && !requiredRoleId.isBlank()) {
            description += "\n\nПодать заявку может только пользователь с ролью <@&" + requiredRoleId + ">.";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📩 Подать заявку")
                .setDescription(description)
                .setColor(new Color(0xff, 0xcb, 0x6b));

        event.getChannel().sendMessageEmbeds(embed.build())
                .addActionRow(Button.primary(BTN_OPEN, "Создать заявку").withEmoji(Emoji.fromUnicode("✉")))
                .queue();

        event.reply("Панель заявок опубликована.").setEphemeral(true).queue();
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
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals(MODAL_ID)) return;
        handleModalSubmit(event);
    }

    private void handleOpen(ButtonInteractionEvent event) {
        String requiredRoleId = plugin.config().staffAppRequiredRoleId();
        if (requiredRoleId != null && !requiredRoleId.isBlank()
                && !hasRequiredRole(event.getGuild(), event.getMember(), event.getUser(), requiredRoleId)) {
            event.reply("У вас нет доступа к подаче заявки.").setEphemeral(true).queue();
            return;
        }

        Modal.Builder builder = Modal.create(MODAL_ID, "Заявка на вступление");
        for (StaffQuestion q : StaffQuestion.ALL) {
            TextInput input = TextInput.create(q.id(), q.label(), q.style())
                    .setRequired(true)
                    .setMaxLength(q.style() == TextInputStyle.PARAGRAPH ? 1000 : 100)
                    .build();
            builder.addActionRow(input);
        }

        com.sunshine.plugin.discord.InteractionLagLogger.timed(plugin, "staff-open", event.getInteraction(),
                event.replyModal(builder.build()));
    }

    private void handleModalSubmit(ModalInteractionEvent event) {
        String requiredRoleId = plugin.config().staffAppRequiredRoleId();
        if (requiredRoleId != null && !requiredRoleId.isBlank()
                && !hasRequiredRole(event.getGuild(), event.getMember(), event.getUser(), requiredRoleId)) {
            event.reply("У вас нет доступа к подаче заявки.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).complete();
        InteractionHook hook = event.getHook();

        try {
            Guild guild = event.getGuild();
            User user = event.getUser();
            Member selfMember = guild.getSelfMember();

            Map<String, String> answers = new LinkedHashMap<>();
            for (StaffQuestion q : StaffQuestion.ALL) {
                answers.put(q.id(), value(event, q.id()));
            }

            GuildInfra.Resolved infra = guildInfra.resolve(guild, "applications", "Заявки на сервер",
                    plugin.config().staffAppCategoryId(), "Recruiter", plugin.config().staffAppReviewerRoleIds());

            TextChannel existing = findOpen(infra.category(), user.getId());
            if (existing != null) {
                hook.sendMessage("У вас уже есть открытая заявка: " + existing.getAsMention()).queue();
                return;
            }

            int number = nextNumber(infra.category(), "app-");
            String channelName = "app-" + pad(number) + "-" + sanitize(user.getName());

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
                    .setTitle("Заявка #" + pad(number))
                    .addField("Автор", user.getAsMention(), false)
                    .setColor(new Color(0xff, 0xcb, 0x6b));
            for (StaffQuestion q : StaffQuestion.ALL) {
                embed.addField(q.label(), answers.getOrDefault(q.id(), "—"), false);
            }

            StringBuilder ping = new StringBuilder(user.getAsMention());
            for (Role role : infra.roles()) ping.append(' ').append(role.getAsMention());

            channel.sendMessage(ping.toString())
                    .setEmbeds(embed.build())
                    .addActionRow(Button.danger(BTN_CLOSE, "Закрыть заявку").withEmoji(Emoji.fromUnicode("🔒")))
                    .queue();

            hook.sendMessage("Заявка отправлена: " + channel.getAsMention()).queue();
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось создать заявку: " + e.getMessage());
            hook.sendMessage("Произошла ошибка, попробуйте ещё раз.").queue();
        }
    }

    private void handleClose(ButtonInteractionEvent event) {
        TextChannel channel = event.getChannel().asTextChannel();
        String topic = channel.getTopic();
        String ownerId = (topic != null && topic.startsWith(TOPIC_PREFIX)) ? topic.substring(TOPIC_PREFIX.length()) : null;

        Member member = event.getMember();
        GuildInfra.Resolved infra = guildInfra.resolve(event.getGuild(), "applications", "Заявки на сервер",
                plugin.config().staffAppCategoryId(), "Recruiter", plugin.config().staffAppReviewerRoleIds());

        boolean isOwner = ownerId != null && ownerId.equals(event.getUser().getId());
        boolean isReviewer = member != null && infra.roles().stream().anyMatch(r -> member.getRoles().contains(r));
        boolean canManage = member != null && member.hasPermission(Permission.MANAGE_CHANNEL);

        if (!isOwner && !isReviewer && !canManage) {
            event.reply("У вас нет прав закрыть эту заявку.").setEphemeral(true).queue();
            return;
        }
        if (channel.getName().startsWith("closed-")) {
            event.reply("Заявка уже закрыта.").setEphemeral(true).queue();
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

        String newName = "closed-" + channel.getName();
        if (newName.length() > 100) newName = newName.substring(0, 100);
        channel.getManager().setName(newName).queue(null, t -> { });

        EmbedBuilder embed = new EmbedBuilder()
                .setDescription("🔒 Заявка закрыта пользователем " + event.getUser().getAsMention())
                .setColor(new Color(0xff, 0x55, 0x55));

        event.replyEmbeds(embed.build())
                .addActionRow(Button.danger(BTN_DELETE, "Удалить заявку").withEmoji(Emoji.fromUnicode("🗑")))
                .queue();
    }

    private void handleDelete(ButtonInteractionEvent event) {
        Member member = event.getMember();
        GuildInfra.Resolved infra = guildInfra.resolve(event.getGuild(), "applications", "Заявки на сервер",
                plugin.config().staffAppCategoryId(), "Recruiter", plugin.config().staffAppReviewerRoleIds());

        boolean isReviewer = member != null && infra.roles().stream().anyMatch(r -> member.getRoles().contains(r));
        boolean canManage = member != null && member.hasPermission(Permission.MANAGE_CHANNEL);

        if (!isReviewer && !canManage) {
            event.reply("Удалять заявки может только команда рассмотрения.").setEphemeral(true).queue();
            return;
        }

        event.reply("Заявка будет удалена через 5 секунд...").queue();
        TextChannel channel = event.getChannel().asTextChannel();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> channel.delete().queue(null, t -> { }), 100L);
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

    /**
     * Есть ли у пользователя нужная роль. Сначала - по данным самой интеракции (обычно этого
     * достаточно), а если там роли не нашлось - на всякий случай ПЕРЕСПРАШИВАЕТ Discord напрямую
     * (REST, .complete() - блокирует ненадолго, но это дешёвый одиночный запрос, а не поход в БД) -
     * вдруг снимок участника в самой интеракции почему-то оказался неполным/устаревшим. Не трогаем
     * gateway-интенты бота ради этой проверки - там риск вообще уронить подключение бота, если
     * привилегированный интент не включён в Discord Developer Portal.
     */
    private boolean hasRequiredRole(Guild guild, Member member, User user, String requiredRoleId) {
        if (member != null && member.getRoles().stream().anyMatch(r -> r.getId().equals(requiredRoleId))) {
            return true;
        }
        if (guild == null) return false;
        try {
            // Таймаут: у Discord на ответ кнопке 3 секунды, дольше ждать нельзя.
            Member fresh = guild.retrieveMember(user).submit().get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            boolean hasRole = fresh != null && fresh.getRoles().stream().anyMatch(r -> r.getId().equals(requiredRoleId));
            String actualRoles = fresh == null ? "<null>"
                    : fresh.getRoles().stream().map(r -> r.getId() + "(" + r.getName() + ")").collect(java.util.stream.Collectors.joining(", "));
            plugin.getLogger().info("Заявка в команду: REST-перепроверка роли для " + user.getName()
                    + " - нужна " + requiredRoleId + ", роли: [" + actualRoles + "] -> " + hasRole);
            return hasRole;
        } catch (Exception e) {
            plugin.getLogger().warning("Заявка в команду: не удалось перепроверить роль через REST для "
                    + user.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
