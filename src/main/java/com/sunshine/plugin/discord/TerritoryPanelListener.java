package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.territory.Community;
import com.sunshine.plugin.territory.CommunityApplication;
import com.sunshine.plugin.territory.CommunityManager;
import com.sunshine.plugin.territory.TagColors;
import com.sunshine.plugin.territory.TerritoryResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Единая панель управления общинами (ТЗ раздел 6) - одна статичная панель в канале
 * ({@code /setup-territory-panel}), кнопки "Подать заявку"/"Моя община"/"Найти общину"/
 * "Админ-панель", плюс канал модерации заявок (Одобрить/Отклонить). Вся бизнес-логика и
 * валидация - в {@link CommunityManager}, этот класс только собирает Discord-интерфейс вокруг неё.
 */
public class TerritoryPanelListener extends ListenerAdapter {

    private static final String P = "sunshine:territory:";

    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    private static final long INVITE_TTL_MS = 10 * 60 * 1000L;

    /** Приглашения "участник, ждём согласия" - в памяти, не в БД (короткоживущие, см. ТЗ раздел 4.1). Ключ - UUID приглашённого. */
    private final Map<UUID, Invite> pendingInvites = new ConcurrentHashMap<>();

    private final SunShinePlugin plugin;

    public TerritoryPanelListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    private CommunityManager manager() {
        return plugin.communityManager();
    }

    private boolean requireManager(IReplyCallback event) {
        if (manager() != null) return true;
        event.reply("Модуль территорий сейчас недоступен (БД не подключена).").setEphemeral(true).queue();
        return false;
    }

    // ==================== Панель ====================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("setup-territory-panel")) return;

        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("У тебя нет прав для этой команды.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏘️ Территории")
                .setDescription("Управление общинами - без слеш-команд, всё через кнопки ниже.")
                .setColor(new Color(87, 242, 135));

        event.replyEmbeds(embed.build())
                .addActionRow(
                        Button.success(P + "apply", "📋 Подать заявку"),
                        Button.primary(P + "my", "🏠 Моя община"),
                        Button.secondary(P + "find", "🔍 Найти общину"),
                        Button.secondary(P + "admin", "🛠 Админ-панель")
                )
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(P)) return;
        String rest = id.substring(P.length());

        if (rest.equals("apply")) { handleApplyButton(event); return; }
        if (rest.equals("my")) { handleMyButton(event); return; }
        if (rest.equals("find")) { handleFindButton(event); return; }
        if (rest.equals("admin")) { handleAdminButton(event); return; }
        if (rest.equals("leave")) { handleLeave(event); return; }

        if (rest.startsWith("owner:add:")) { handleOwnerAdd(event, id(rest, "owner:add:")); return; }
        if (rest.startsWith("owner:kick:")) { openKickSelect(event, id(rest, "owner:kick:"), false); return; }
        if (rest.startsWith("owner:expand:")) { handleOwnerExpand(event, id(rest, "owner:expand:")); return; }
        if (rest.startsWith("owner:closed:")) { handleToggleClosed(event, id(rest, "owner:closed:"), false); return; }
        if (rest.startsWith("owner:transfer:")) { openTransferSelect(event, id(rest, "owner:transfer:"), false); return; }
        if (rest.startsWith("owner:delete:")) { openDeleteConfirm(event, id(rest, "owner:delete:"), false); return; }
        if (rest.startsWith("owner:color:")) { openColorSelect(event, id(rest, "owner:color:")); return; }
        if (rest.startsWith("owner:bold:")) { handleToggleBold(event, id(rest, "owner:bold:")); return; }

        if (rest.startsWith("admin:kick:")) { openKickSelect(event, id(rest, "admin:kick:"), true); return; }
        if (rest.startsWith("admin:transfer:")) { openTransferSelect(event, id(rest, "admin:transfer:"), true); return; }
        if (rest.startsWith("admin:restrict:")) { openRestrictSelect(event, id(rest, "admin:restrict:")); return; }
        if (rest.startsWith("admin:closed:")) { handleToggleClosed(event, id(rest, "admin:closed:"), true); return; }
        if (rest.startsWith("admin:delete:")) { openDeleteConfirm(event, id(rest, "admin:delete:"), true); return; }

        if (rest.startsWith("approve:")) { handleApprove(event, id(rest, "approve:")); return; }
        if (rest.startsWith("reject:")) { openRejectModal(event, id(rest, "reject:")); return; }

        if (rest.startsWith("invite:accept:")) { handleInviteAccept(event, id(rest, "invite:accept:")); return; }
        if (rest.startsWith("invite:decline:")) { handleInviteDecline(event, id(rest, "invite:decline:")); return; }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(P)) return;
        String rest = id.substring(P.length());
        String value = event.getValues().isEmpty() ? null : event.getValues().get(0);
        if (value == null) return;

        if (rest.startsWith("select:kick:")) { doKick(event, id(rest, "select:kick:"), value, false); return; }
        if (rest.startsWith("select:admin:kick:")) { doKick(event, id(rest, "select:admin:kick:"), value, true); return; }
        if (rest.startsWith("select:transfer:")) { doTransfer(event, id(rest, "select:transfer:"), value, false); return; }
        if (rest.startsWith("select:admin:transfer:")) { doTransfer(event, id(rest, "select:admin:transfer:"), value, true); return; }
        if (rest.startsWith("select:admin:restrict:")) { doRestrict(event, id(rest, "select:admin:restrict:"), event.getValues()); return; }
        if (rest.startsWith("select:color:")) { doSetColor(event, id(rest, "select:color:"), value); return; }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(P)) return;
        String rest = id.substring(P.length());

        if (rest.equals("modal:apply")) { handleApplySubmit(event); return; }
        if (rest.equals("modal:find")) { handleFindSubmit(event); return; }
        if (rest.equals("modal:adminlookup")) { handleAdminLookupSubmit(event); return; }
        if (rest.startsWith("modal:invite:")) { handleInviteSubmit(event, id(rest, "modal:invite:")); return; }
        if (rest.startsWith("modal:expand:")) { handleExpandSubmit(event, id(rest, "modal:expand:")); return; }
        if (rest.startsWith("modal:reject:")) { handleRejectSubmit(event, id(rest, "modal:reject:")); return; }
        if (rest.startsWith("modal:delete:")) { handleDeleteSubmit(event, id(rest, "modal:delete:"), false); return; }
        if (rest.startsWith("modal:admindelete:")) { handleDeleteSubmit(event, id(rest, "modal:admindelete:"), true); return; }
    }

    private int id(String rest, String prefix) {
        return Integer.parseInt(rest.substring(prefix.length()));
    }

    // ==================== Подача заявки ====================

    private void handleApplyButton(ButtonInteractionEvent event) {
        if (!requireManager(event)) return;
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (uuid == null) {
            event.reply("Сначала привяжи аккаунт (`/link` в игре).").setEphemeral(true).queue();
            return;
        }

        Modal modal = Modal.create(P + "modal:apply", "Заявка на создание общины")
                .addActionRow(TextInput.create("name", "Название общины", TextInputStyle.SHORT).setRequired(true).setMaxLength(64).build())
                .addActionRow(TextInput.create("tag", "Тег (2-6 латинских букв/цифр)", TextInputStyle.SHORT).setRequired(true).setMaxLength(6).build())
                .addActionRow(TextInput.create("coords", "Координаты (2 или 4 угла, строка на каждый)", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setPlaceholder("100 200\n150 200\n150 250\n100 250").build())
                .addActionRow(TextInput.create("description", "Цель создания", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(500).build())
                .addActionRow(TextInput.create("confirm", "Согласен(на) с правилами? (Да/Нет)", TextInputStyle.SHORT).setRequired(true).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleApplySubmit(ModalInteractionEvent event) {
        if (!requireManager(event)) return;
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (uuid == null) {
            event.reply("Сначала привяжи аккаунт (`/link` в игре).").setEphemeral(true).queue();
            return;
        }
        if (!isYes(value(event, "confirm"))) {
            event.reply("Нужно подтвердить согласие с правилами ('Да').").setEphemeral(true).queue();
            return;
        }
        int[] coords = parseCoords(value(event, "coords"));
        if (coords == null) {
            event.reply("Не удалось разобрать координаты - нужно 2 или 4 строки вида `x z`.").setEphemeral(true).queue();
            return;
        }
        String name = value(event, "name").trim();
        String tag = value(event, "tag").trim().toUpperCase(Locale.ROOT);
        String description = value(event, "description").trim();

        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Integer> result = manager().submitCreateApplication(uuid, event.getUser().getId(),
                    name, tag, coords[0], coords[1], coords[2], coords[3], description);
            if (!result.isOk()) {
                event.getHook().editOriginal("❌ " + result.error()).queue();
                return;
            }
            int applicationId = result.value();
            postApplicationForReview(applicationId, event.getUser());
            event.getHook().editOriginal("✅ Заявка отправлена, ожидай решения администрации.").queue();
        });
    }

    private void postApplicationForReview(int applicationId, User applicant) {
        String channelId = plugin.config().territoryReviewChannelId();
        if (channelId.isBlank() || !plugin.discordBot().isReady()) return;
        MessageChannel channel = plugin.discordBot().jda().getTextChannelById(channelId);
        if (channel == null) return;

        CommunityApplication application = safeGetApplication(applicationId);
        if (application == null) return;

        EmbedBuilder embed = buildApplicationEmbed(application, applicant);
        channel.sendMessageEmbeds(embed.build())
                .addActionRow(
                        Button.success(P + "approve:" + applicationId, "Одобрить"),
                        Button.danger(P + "reject:" + applicationId, "Отклонить")
                )
                .queue(message -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.territoryDatabase().setApplicationAdminMessage(applicationId, channelId, message.getId());
                    } catch (Exception ignored) {
                        // best-effort - если не сохранилось, embed просто не отредактируется при решении, не критично
                    }
                }), throwable -> { });
    }

    private EmbedBuilder buildApplicationEmbed(CommunityApplication application, User applicant) {
        boolean expand = application.type() == CommunityApplication.Type.EXPAND;
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(expand ? "📐 Заявка на расширение территории" : "📋 Заявка на создание общины")
                .setColor(new Color(88, 101, 242))
                .addField("От кого", applicant != null ? applicant.getAsMention() : application.applicantUuid().toString(), true);
        if (!expand) {
            embed.addField("Название", application.proposedName(), true)
                    .addField("Тег", application.proposedTag(), true);
        } else {
            embed.addField("Общины", "#" + application.targetCommunityId(), true);
        }
        embed.addField("Координаты", application.minX() + "," + application.minZ() + " → " + application.maxX() + "," + application.maxZ(), false)
                .addField("Размер", application.sizeLabel(), true)
                .addField("Описание", application.description() == null || application.description().isBlank() ? "-" : application.description(), false);
        return embed;
    }

    private void handleApprove(ButtonInteractionEvent event, int applicationId) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Community> result = manager().approveApplication(applicationId, event.getUser().getName());
            CommunityApplication application = safeGetApplication(applicationId);
            if (!result.isOk()) {
                event.getHook().editOriginalComponents(List.<ActionRow>of()).queue();
                event.getHook().sendMessage("❌ " + result.error()).setEphemeral(true).queue();
                return;
            }
            Community community = result.value();
            List<net.dv8tion.jda.api.entities.MessageEmbed> embeds = event.getMessage().getEmbeds();
            EmbedBuilder edited = embeds.isEmpty() ? new EmbedBuilder() : new EmbedBuilder(embeds.get(0));
            edited.addField("Решение", "✅ Одобрено администратором " + event.getUser().getAsMention(), false);
            edited.setColor(new Color(87, 242, 135));
            event.getHook().editOriginalEmbeds(edited.build()).setComponents(List.<ActionRow>of()).queue();

            if (application != null) {
                dmApplicant(application.applicantDiscordId(), "✅ Твоя заявка по территории **" + community.name()
                        + "** одобрена!" + (application.type() == CommunityApplication.Type.EXPAND
                        ? " Новые границы: " + community.minX() + "," + community.minZ() + " → " + community.maxX() + "," + community.maxZ() + "."
                        : " Тег общины: `" + community.tag() + "`."));
            }
        });
    }

    private void openRejectModal(ButtonInteractionEvent event, int applicationId) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        Modal modal = Modal.create(P + "modal:reject:" + applicationId, "Отклонить заявку")
                .addActionRow(TextInput.create("reason", "Причина отклонения", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(500).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleRejectSubmit(ModalInteractionEvent event, int applicationId) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        String reason = value(event, "reason").trim();
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CommunityApplication application = safeGetApplication(applicationId);
            TerritoryResult<Void> result = manager().rejectApplication(applicationId, event.getUser().getName(), reason);
            if (!result.isOk()) {
                event.getHook().editOriginal("❌ " + result.error()).queue();
                return;
            }
            event.getHook().editOriginal("Заявка отклонена, причина отправлена заявителю.").queue();
            if (application != null && application.adminChannelId() != null && application.adminMessageId() != null) {
                var channel = plugin.discordBot().jda().getTextChannelById(application.adminChannelId());
                if (channel != null) {
                    channel.retrieveMessageById(application.adminMessageId()).queue(message -> {
                        EmbedBuilder edited = message.getEmbeds().isEmpty() ? new EmbedBuilder() : new EmbedBuilder(message.getEmbeds().get(0));
                        edited.addField("Решение", "❌ Отклонено администратором " + event.getUser().getAsMention() + "\nПричина: " + reason, false);
                        edited.setColor(new Color(237, 66, 69));
                        message.editMessageEmbeds(edited.build()).setComponents(List.<ActionRow>of()).queue(null, t -> { });
                    }, t -> { });
                }
                dmApplicant(application.applicantDiscordId(), "❌ Твоя заявка по территории отклонена.\nПричина: " + reason);
            }
        });
    }

    private void dmApplicant(String discordId, String text) {
        if (discordId == null || discordId.isBlank() || !plugin.discordBot().isReady()) return;
        plugin.discordBot().jda().retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(dm -> dm.sendMessage(text).queue(null, t -> { }), t -> { }),
                t -> { });
    }

    // ==================== Моя община ====================

    private void handleMyButton(ButtonInteractionEvent event) {
        if (!requireManager(event)) return;
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (uuid == null) {
            event.reply("Сначала привяжи аккаунт (`/link` в игре).").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = manager().findCommunityForPlayer(uuid);
            if (community == null) {
                event.getHook().editOriginal("У тебя нет общины.")
                        .setComponents(ActionRow.of(Button.success(P + "apply", "📋 Подать заявку")))
                        .queue();
                return;
            }
            if (community.ownerUuid().equals(uuid)) {
                sendOwnerCard(event.getHook(), community);
            } else {
                event.getHook().editOriginalEmbeds(communityEmbed(community).build())
                        .setComponents(ActionRow.of(Button.danger(P + "leave", "🚪 Покинуть общину")))
                        .queue();
            }
        });
    }

    private void sendOwnerCard(net.dv8tion.jda.api.interactions.InteractionHook hook, Community community) {
        List<Button> row1 = List.of(
                Button.primary(P + "owner:add:" + community.id(), "➕ Добавить"),
                Button.secondary(P + "owner:kick:" + community.id(), "➖ Исключить"),
                Button.secondary(P + "owner:transfer:" + community.id(), "👑 Передать")
        );
        List<Button> row2 = List.of(
                Button.secondary(P + "owner:expand:" + community.id(), "📐 Расширить"),
                Button.of(community.closed() ? net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SUCCESS
                                : net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SECONDARY,
                        P + "owner:closed:" + community.id(), community.closed() ? "🔓 Открыть вход" : "🔒 Закрыть вход"),
                Button.danger(P + "owner:delete:" + community.id(), "🗑 Удалить")
        );
        List<Button> row3 = List.of(
                Button.secondary(P + "owner:color:" + community.id(), "🎨 Цвет тега"),
                Button.of(community.tagBold() ? net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SUCCESS
                                : net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SECONDARY,
                        P + "owner:bold:" + community.id(), community.tagBold() ? "🅱 Жирный: Вкл" : "🅱 Жирный: Выкл")
        );
        hook.editOriginalEmbeds(communityEmbed(community).build())
                .setComponents(ActionRow.of(row1), ActionRow.of(row2), ActionRow.of(row3))
                .queue();
    }

    private void handleLeave(ButtonInteractionEvent event) {
        if (!requireManager(event)) return;
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (uuid == null) return;
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result = manager().leaveCommunity(uuid);
            event.getHook().editOriginal(result.isOk() ? "Ты покинул(а) общину." : "❌ " + result.error()).queue();
        });
    }

    // ==================== Цвет/жирность клан-тега ====================

    private void openColorSelect(ButtonInteractionEvent event, int communityId) {
        if (!requireManager(event)) return;
        List<SelectOption> options = TagColors.palette().entrySet().stream()
                .map(e -> SelectOption.of(e.getValue(), e.getKey()))
                .collect(Collectors.toList());
        StringSelectMenu menu = StringSelectMenu.create(P + "select:color:" + communityId).addOptions(options).build();
        event.reply("Выбери цвет тега:").setEphemeral(true).addActionRow(menu).queue();
    }

    private void doSetColor(StringSelectInteractionEvent event, int communityId, String colorCode) {
        if (!requireManager(event)) return;
        UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (ownerUuid == null) return;
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result = manager().setTagColor(communityId, ownerUuid, colorCode);
            event.getHook().editOriginal(result.isOk() ? "✅ Цвет тега обновлён." : "❌ " + result.error())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    private void handleToggleBold(ButtonInteractionEvent event, int communityId) {
        if (!requireManager(event)) return;
        UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (ownerUuid == null) return;
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = safeGetCommunity(communityId);
            if (community == null) return;
            manager().setTagBold(communityId, ownerUuid, !community.tagBold());
            Community updated = safeGetCommunity(communityId);
            if (updated != null) sendOwnerCard(event.getHook(), updated);
        });
    }

    private EmbedBuilder communityEmbed(Community community) {
        List<UUID> members = manager().members(community.id());
        String ownerName = plugin.linkManager().nameFor(community.ownerUuid());
        String memberNames = members.stream()
                .map(u -> plugin.linkManager().nameFor(u))
                .filter(n -> n != null)
                .collect(Collectors.joining(", "));
        return new EmbedBuilder()
                .setTitle("🏘️ " + community.tag() + " — " + community.name())
                .setColor(new Color(87, 242, 135))
                .addField("Владелец", ownerName == null ? community.ownerUuid().toString() : ownerName, true)
                .addField("Участники (" + members.size() + ")", memberNames.isBlank() ? "-" : memberNames, false)
                .addField("Координаты", community.minX() + "," + community.minZ() + " → " + community.maxX() + "," + community.maxZ(), true)
                .addField("Размер", community.sizeLabel(), true)
                .addField("Доступ", community.closed() ? "🔒 Закрыта для посторонних" : "🔓 Открыта", true)
                .addField("Ограничения", community.restrictions().isEmpty() ? "нет" : String.join(", ", community.restrictions()), true);
    }

    // ==================== Приглашение участника ====================

    private void handleOwnerAdd(ButtonInteractionEvent event, int communityId) {
        Modal modal = Modal.create(P + "modal:invite:" + communityId, "Пригласить игрока")
                .addActionRow(TextInput.create("nickname", "Ник игрока", TextInputStyle.SHORT).setRequired(true).setMaxLength(16).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleInviteSubmit(ModalInteractionEvent event, int communityId) {
        if (!requireManager(event)) return;
        UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        String nickname = value(event, "nickname").trim();
        UUID inviteeUuid = plugin.linkManager().uuidForName(nickname);
        if (ownerUuid == null) return;
        if (inviteeUuid == null) {
            event.reply("Игрок '" + nickname + "' не найден (не заходил на сервер или не привязан к Discord).").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Community> check = manager().canInvite(communityId, ownerUuid, inviteeUuid);
            if (!check.isOk()) {
                event.getHook().editOriginal("❌ " + check.error()).queue();
                return;
            }
            Community community = check.value();
            String inviteeDiscordId = plugin.linkManager().discordIdFor(inviteeUuid);
            if (inviteeDiscordId == null || inviteeDiscordId.isBlank()) {
                event.getHook().editOriginal("❌ У игрока '" + nickname + "' не привязан Discord - пригласить нельзя.").queue();
                return;
            }
            pendingInvites.put(inviteeUuid, new Invite(communityId, ownerUuid, System.currentTimeMillis() + INVITE_TTL_MS));
            event.getHook().editOriginal("Приглашение отправлено игроку `" + nickname + "` в ЛС, ждём подтверждения.").queue();

            plugin.discordBot().jda().retrieveUserById(inviteeDiscordId).queue(user -> user.openPrivateChannel().queue(dm -> {
                EmbedBuilder invite = new EmbedBuilder()
                        .setTitle("Приглашение в общину")
                        .setDescription("Тебя приглашают в общину **" + community.tag() + " — " + community.name() + "**.")
                        .setColor(new Color(88, 101, 242));
                dm.sendMessageEmbeds(invite.build())
                        .addActionRow(
                                Button.success(P + "invite:accept:" + communityId, "Принять"),
                                Button.danger(P + "invite:decline:" + communityId, "Отклонить")
                        )
                        .queue(null, t -> { });
            }, t -> { }), t -> { });
        });
    }

    private void handleInviteAccept(ButtonInteractionEvent event, int communityId) {
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        Invite invite = uuid == null ? null : pendingInvites.get(uuid);
        if (invite == null || invite.communityId != communityId || invite.isExpired()) {
            event.editMessage("Приглашение больше не действительно.").setComponents(List.<ActionRow>of()).queue();
            return;
        }
        if (!requireManager(event)) return;
        pendingInvites.remove(uuid);
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result = manager().addMember(communityId, invite.ownerUuid, uuid);
            event.getHook().editOriginal(result.isOk() ? "✅ Ты присоединился(лась) к общине!" : "❌ " + result.error())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    private void handleInviteDecline(ButtonInteractionEvent event, int communityId) {
        UUID uuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (uuid != null) pendingInvites.remove(uuid);
        event.editMessage("Приглашение отклонено.").setComponents(List.<ActionRow>of()).queue();
    }

    // ==================== Исключение / передача владения ====================

    private void openKickSelect(ButtonInteractionEvent event, int communityId, boolean admin) {
        if (admin && !hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = safeGetCommunity(communityId);
            if (community == null) { event.getHook().editOriginal("Община не найдена.").queue(); return; }
            List<SelectOption> options = memberOptions(community, true);
            if (options.isEmpty()) { event.getHook().editOriginal("Кроме владельца, исключать некого.").queue(); return; }
            String selectId = P + "select:" + (admin ? "admin:kick:" : "kick:") + communityId;
            event.getHook().editOriginal("Кого исключить из **" + community.name() + "**?")
                    .setComponents(ActionRow.of(StringSelectMenu.create(selectId).addOptions(options).build()))
                    .queue();
        });
    }

    private void doKick(StringSelectInteractionEvent event, int communityId, String uuidValue, boolean admin) {
        if (admin && !hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        UUID target = UUID.fromString(uuidValue);
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result;
            if (admin) {
                result = manager().adminKickMember(communityId, target, event.getUser().getName());
            } else {
                UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
                result = ownerUuid == null ? TerritoryResult.fail("Аккаунт не привязан.") : manager().kickMember(communityId, ownerUuid, target);
            }
            event.getHook().editOriginal(result.isOk() ? "✅ Игрок исключён." : "❌ " + result.error())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    private void openTransferSelect(ButtonInteractionEvent event, int communityId, boolean admin) {
        if (admin && !hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = safeGetCommunity(communityId);
            if (community == null) { event.getHook().editOriginal("Община не найдена.").queue(); return; }
            List<SelectOption> options = memberOptions(community, true);
            if (options.isEmpty()) { event.getHook().editOriginal("Нет других участников, кому передать права.").queue(); return; }
            String selectId = P + "select:" + (admin ? "admin:transfer:" : "transfer:") + communityId;
            event.getHook().editOriginal("Кому передать права владельца в **" + community.name() + "**?")
                    .setComponents(ActionRow.of(StringSelectMenu.create(selectId).addOptions(options).build()))
                    .queue();
        });
    }

    private void doTransfer(StringSelectInteractionEvent event, int communityId, String uuidValue, boolean admin) {
        if (admin && !hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        UUID target = UUID.fromString(uuidValue);
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result;
            if (admin) {
                result = manager().adminTransferOwnership(communityId, target, event.getUser().getName());
            } else {
                UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
                result = ownerUuid == null ? TerritoryResult.fail("Аккаунт не привязан.") : manager().transferOwnership(communityId, ownerUuid, target);
            }
            event.getHook().editOriginal(result.isOk() ? "✅ Права переданы." : "❌ " + result.error())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    private List<SelectOption> memberOptions(Community community, boolean excludeOwner) {
        List<SelectOption> options = new ArrayList<>();
        for (UUID member : manager().members(community.id())) {
            if (excludeOwner && member.equals(community.ownerUuid())) continue;
            String name = plugin.linkManager().nameFor(member);
            options.add(SelectOption.of(name == null ? member.toString() : name, member.toString()));
            if (options.size() >= 25) break; // лимит Discord на select menu
        }
        return options;
    }

    // ==================== Расширение / закрытие / удаление ====================

    private void handleOwnerExpand(ButtonInteractionEvent event, int communityId) {
        // Label "Новые координаты: строка 1 ..." был 48 символов - за лимитом Discord в 45 (см.
        // openDeleteConfirm), сборка модалки падала до event.replyModal() -> "не ответило вовремя".
        Modal modal = Modal.create(P + "modal:expand:" + communityId, "Расширить территорию")
                .addActionRow(TextInput.create("coords", "Координаты (2 или 4 угла, строка на каждый)", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setPlaceholder("90 190\n160 190\n160 260\n90 260").build())
                .addActionRow(TextInput.create("reason", "Причина расширения", TextInputStyle.PARAGRAPH).setRequired(true).setMaxLength(500).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleExpandSubmit(ModalInteractionEvent event, int communityId) {
        if (!requireManager(event)) return;
        UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
        if (ownerUuid == null) return;
        int[] coords = parseCoords(value(event, "coords"));
        if (coords == null) {
            event.reply("Не удалось разобрать координаты - нужно 2 или 4 строки вида `x z`.").setEphemeral(true).queue();
            return;
        }
        String reason = value(event, "reason").trim();
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Integer> result = manager().submitExpandApplication(ownerUuid, communityId, coords[0], coords[1], coords[2], coords[3], reason);
            if (!result.isOk()) {
                event.getHook().editOriginal("❌ " + result.error()).queue();
                return;
            }
            postApplicationForReview(result.value(), event.getUser());
            event.getHook().editOriginal("✅ Заявка на расширение отправлена, ожидай решения администрации.").queue();
        });
    }

    private void handleToggleClosed(ButtonInteractionEvent event, int communityId, boolean admin) {
        if (!requireManager(event)) return;
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = safeGetCommunity(communityId);
            if (community == null) return;
            boolean newValue = !community.closed();
            TerritoryResult<Void> result;
            if (admin) {
                // Админ-панель не завязана на владельца - используем тот же DB/bridge-путь через владельца самой общины.
                result = manager().setClosed(communityId, community.ownerUuid(), newValue);
            } else {
                UUID ownerUuid = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
                result = ownerUuid == null ? TerritoryResult.fail("Аккаунт не привязан.") : manager().setClosed(communityId, ownerUuid, newValue);
            }
            if (!result.isOk()) {
                event.getHook().sendMessage("❌ " + result.error()).setEphemeral(true).queue();
                return;
            }
            Community updated = safeGetCommunity(communityId);
            if (!admin && updated != null) sendOwnerCard(event.getHook(), updated);
        });
    }

    private void openDeleteConfirm(ButtonInteractionEvent event, int communityId, boolean admin) {
        try {
            if (!requireManager(event)) return;
            // Модалка обязана быть НЕМЕДЛЕННЫМ ответом на клик - никакого deferReply() или похода в
            // БД до неё (иначе Discord не дождётся ответа за 3с, см. getCached()).
            Community community = manager().getCached(communityId);
            String name = community == null || community.name() == null || community.name().isBlank()
                    ? "?" : community.name();
            Modal.Builder builder = Modal.create(P + "modal:" + (admin ? "admindelete:" : "delete:") + communityId, "Удалить общину");
            TextInput.Builder input = TextInput.create("confirm", "Название общины для подтверждения", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setPlaceholder(name);
            builder.addActionRow(input.build());
            event.replyModal(builder.build()).queue();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Territory: openDeleteConfirm упал с исключением (община #" + communityId + ")", e);
            if (!event.isAcknowledged()) {
                event.reply("❌ Внутренняя ошибка, смотри консоль сервера.").setEphemeral(true).queue(null, t -> { });
            }
        }
    }

    private void handleDeleteSubmit(ModalInteractionEvent event, int communityId, boolean admin) {
        try {
            if (admin && !hasAdminRole(event.getMember())) {
                event.reply("Недостаточно прав.").setEphemeral(true).queue();
                return;
            }
            if (!requireManager(event)) return;
            event.deferReply(true).queue();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Community community = safeGetCommunity(communityId);
                    if (community == null) { event.getHook().editOriginal("Община не найдена.").queue(); return; }
                    if (!value(event, "confirm").trim().equalsIgnoreCase(community.name())) {
                        event.getHook().editOriginal("❌ Название не совпадает, удаление отменено.").queue();
                        return;
                    }
                    UUID requester = plugin.linkManager().uuidForDiscordId(event.getUser().getId());
                    TerritoryResult<Void> result = manager().deleteCommunity(communityId, requester == null ? community.ownerUuid() : requester, admin, event.getUser().getName());
                    event.getHook().editOriginal(result.isOk() ? "✅ Община удалена." : "❌ " + result.error()).queue();
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Territory: handleDeleteSubmit (async) упал с исключением (община #" + communityId + ")", e);
                    event.getHook().editOriginal("❌ Внутренняя ошибка, смотри консоль сервера.").queue(null, t -> { });
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Territory: handleDeleteSubmit упал с исключением до deferReply (община #" + communityId + ")", e);
            if (!event.isAcknowledged()) {
                event.reply("❌ Внутренняя ошибка, смотри консоль сервера.").setEphemeral(true).queue(null, t -> { });
            }
        }
    }

    // ==================== Поиск / админ-панель ====================

    private void handleFindButton(ButtonInteractionEvent event) {
        Modal modal = Modal.create(P + "modal:find", "Найти общину")
                .addActionRow(TextInput.create("nickname", "Ник игрока", TextInputStyle.SHORT).setRequired(true).setMaxLength(16).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleFindSubmit(ModalInteractionEvent event) {
        if (!requireManager(event)) return;
        String nickname = value(event, "nickname").trim();
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = plugin.linkManager().uuidForName(nickname);
            Community community = uuid == null ? null : manager().findCommunityForPlayer(uuid);
            if (community == null) {
                event.getHook().editOriginal("У игрока '" + nickname + "' нет общины.").queue();
                return;
            }
            event.getHook().editOriginalEmbeds(communityEmbed(community).build()).queue();
        });
    }

    private void handleAdminButton(ButtonInteractionEvent event) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        Modal modal = Modal.create(P + "modal:adminlookup", "Админ-панель общины")
                .addActionRow(TextInput.create("query", "Название общины или ник участника", TextInputStyle.SHORT).setRequired(true).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void handleAdminLookupSubmit(ModalInteractionEvent event) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        String query = value(event, "query").trim();
        event.deferReply(true).queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Community community = manager().findCommunityByName(query);
            if (community == null) {
                UUID uuid = plugin.linkManager().uuidForName(query);
                community = uuid == null ? null : manager().findCommunityForPlayer(uuid);
            }
            if (community == null) {
                event.getHook().editOriginal("Не нашёл общину по '" + query + "'.").queue();
                return;
            }
            List<Button> row1 = List.of(
                    Button.secondary(P + "admin:kick:" + community.id(), "➖ Кикнуть"),
                    Button.secondary(P + "admin:transfer:" + community.id(), "👑 Передать"),
                    Button.secondary(P + "admin:restrict:" + community.id(), "⚠ Ограничения")
            );
            List<Button> row2 = List.of(
                    Button.of(community.closed() ? net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SUCCESS
                                    : net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SECONDARY,
                            P + "admin:closed:" + community.id(), community.closed() ? "🔓 Открыть вход" : "🔒 Закрыть вход"),
                    Button.danger(P + "admin:delete:" + community.id(), "🗑 Удалить")
            );
            event.getHook().editOriginalEmbeds(communityEmbed(community).build())
                    .setComponents(ActionRow.of(row1), ActionRow.of(row2))
                    .queue();
        });
    }

    private void openRestrictSelect(ButtonInteractionEvent event, int communityId) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        // Некэшированный (блокирующий) поиск здесь недопустим - см. openDeleteConfirm/getCached().
        Community community = manager().getCached(communityId);
        Set<String> current = community == null ? Set.of() : community.restrictions();
        StringSelectMenu menu = StringSelectMenu.create(P + "select:admin:restrict:" + communityId)
                .setMinValues(0).setMaxValues(3)
                .addOptions(
                        SelectOption.of("Заморозка (lock)", "lock").withDefault(current.contains("lock")),
                        SelectOption.of("Принудительный PvP (pvp_forced)", "pvp_forced").withDefault(current.contains("pvp_forced")),
                        SelectOption.of("Блок приглашений (no_adds)", "no_adds").withDefault(current.contains("no_adds"))
                ).build();
        event.reply("Выбери активные ограничения (можно несколько, можно ни одного):")
                .setEphemeral(true).addActionRow(menu).queue();
    }

    private void doRestrict(StringSelectInteractionEvent event, int communityId, List<String> values) {
        if (!hasAdminRole(event.getMember())) {
            event.reply("Недостаточно прав.").setEphemeral(true).queue();
            return;
        }
        if (!requireManager(event)) return;
        event.deferEdit().queue();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TerritoryResult<Void> result = manager().adminSetRestrictions(communityId, Set.copyOf(values), event.getUser().getName());
            event.getHook().editOriginal(result.isOk() ? "✅ Ограничения обновлены: "
                    + (values.isEmpty() ? "нет" : String.join(", ", values)) : "❌ " + result.error())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    // ==================== Утилиты ====================

    private boolean hasAdminRole(Member member) {
        List<String> roleIds = plugin.config().territoryAdminRoleIds();
        if (roleIds.isEmpty() || member == null) return false; // fail-closed
        return member.getRoles().stream().anyMatch(r -> roleIds.contains(r.getId()));
    }

    private Community safeGetCommunity(int id) {
        try {
            return plugin.territoryDatabase().getCommunity(id);
        } catch (Exception e) {
            return null;
        }
    }

    private CommunityApplication safeGetApplication(int id) {
        try {
            return plugin.territoryDatabase().getApplication(id);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isYes(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.equals("да") || v.equals("yes") || v.equals("y") || v.equals("д");
    }

    /** Парсит 2 строки вида "x z" в [x1,z1,x2,z2], или null при некорректном формате. */
    /**
     * Принимает 2 ИЛИ 4 угла (по одному "x z" на строку) - территория всё равно прямоугольник
     * (ТЗ раздел 0.1, совместимость с TerritoryTitles), поэтому просто считаем границы (min/max)
     * по всем введённым точкам. С 4 углами (реальные углы участка) результат совпадает 1-в-1 с тем
     * же, что дали бы только 2 противоположных - удобнее вводить, ничего не теряется.
     */
    private int[] parseCoords(String raw) {
        Matcher matcher = NUMBER.matcher(raw);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        if (numbers.size() < 4 || numbers.size() % 2 != 0) return null; // минимум 2 точки (x z каждая)

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < numbers.size(); i += 2) {
            int x = numbers.get(i);
            int z = numbers.get(i + 1);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private String value(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString();
    }

    private record Invite(int communityId, UUID ownerUuid, long expiresAtMs) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
