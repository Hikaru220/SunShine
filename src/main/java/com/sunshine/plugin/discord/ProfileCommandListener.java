package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.profile.ModerationExecutor;
import com.sunshine.plugin.profile.ProfileService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Discord-команда {@code /profile <ник>}: показывает всё, что SunShine знает об игроке (ник,
 * привязка Discord, время онлайна, группа LuckPerms + когда истекает, кто принял заявку, история
 * наказаний из БД FlectonePulse), плюс кнопки модерации (бан/мут/кик/варн через FlectonePulse,
 * шорткаты на его banlist/mutelist/warnlist, геолокация через ip-api.com и показ IP).
 * <p>
 * Кнопки и сама команда доступны только участникам с ролью {@code profile.moderator-role-id} -
 * если роль не задана в конфиге, модерация недоступна никому (fail-closed), сама карточка
 * профиля при этом всё равно показывается (не секретная).
 */
public class ProfileCommandListener extends ListenerAdapter {

    private static final String BTN_PREFIX = "sunshine:profile:";
    private static final String MODAL_PREFIX = "sunshine:profilemodal:";
    private static final String INPUT_DURATION = "duration";
    private static final String INPUT_REASON = "reason";

    private final SunShinePlugin plugin;
    private final ProfileService profileService;
    private final ModerationExecutor moderationExecutor;

    public ProfileCommandListener(SunShinePlugin plugin) {
        this.plugin = plugin;
        this.profileService = new ProfileService(plugin);
        this.moderationExecutor = new ModerationExecutor(plugin);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("profile")) return;

        OptionMapping option = event.getOption("nickname");
        if (option == null) {
            event.reply("Укажи ник игрока.").setEphemeral(true).queue();
            return;
        }
        String nickname = option.getAsString();

        // Замер: сколько событие "ехало" до нас и сколько занял сам запрос подтверждения (ack) к Discord.
        final long ackStart = System.nanoTime();
        final long eventAge = java.time.temporal.ChronoUnit.MILLIS.between(
                event.getInteraction().getTimeCreated(), java.time.OffsetDateTime.now());
        event.deferReply().queue(
                ok -> plugin.getLogger().info("[lag:profile] событие пришло через " + eventAge + " мс, ack занял "
                        + (System.nanoTime() - ackStart) / 1_000_000 + " мс"),
                err -> plugin.getLogger().warning("[lag:profile] ack НЕ удался: событие пришло через " + eventAge
                        + " мс, запрос шёл " + (System.nanoTime() - ackStart) / 1_000_000 + " мс, ошибка: " + err.getMessage()));
        profileService.load(nickname).whenComplete((profile, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("/profile: ошибка сбора данных по '" + nickname + "': " + throwable.getMessage());
                event.getHook().editOriginal("Не удалось собрать данные по игроку — смотри консоль.").queue();
                return;
            }
            if (profile == null) {
                event.getHook().editOriginal("Игрок '" + nickname + "' не найден (SunShine его никогда не видел).").queue();
                return;
            }

            boolean canModerate = hasModeratorRole(event.getMember());
            event.getHook().editOriginalEmbeds(buildEmbed(profile).build())
                    .setComponents(canModerate ? actionRows(profile.nickname(), profile.lastIp()) : List.of())
                    .queue();
        });
    }

    private boolean hasModeratorRole(Member member) {
        List<String> roleIds = plugin.config().profileModeratorRoleIds();
        if (roleIds.isEmpty() || member == null) return false; // fail-closed
        return member.getRoles().stream().anyMatch(r -> roleIds.contains(r.getId()));
    }

    private EmbedBuilder buildEmbed(ProfileService.Profile profile) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Профиль: " + profile.nickname())
                .setColor(new Color(88, 101, 242));

        embed.addField("Discord", profile.discordId() == null ? "не привязан" : "<@" + profile.discordId() + ">", true);
        embed.addField("Сейчас", profile.online() ? "🟢 онлайн" : "⚪ офлайн", true);
        embed.addField("Вайтлист", profile.whitelisted() ? "✅ да" : "❌ нет", true);
        embed.addField("Время онлайна", formatPlaytime(profile.playtimeSeconds()), true);

        String group = profile.groupName() == null ? "неизвестно (нет LuckPerms)" : profile.groupName();
        if (profile.groupExpiryEpochSeconds() != null) {
            group += " (до <t:" + profile.groupExpiryEpochSeconds() + ":R>)";
        }
        embed.addField("Группа", group, true);

        if (profile.applicationStatus() == null) {
            embed.addField("Заявка", "не подавал(а)", false);
        } else {
            String submitted = profile.applicationSubmittedAt() == null ? "?"
                    : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                            .format(Instant.ofEpochMilli(profile.applicationSubmittedAt())
                                    .atZone(java.time.ZoneId.systemDefault()));
            String decided = profile.applicationDecidedBy() == null ? "ещё не рассмотрена"
                    : profile.applicationDecidedBy();
            embed.addField("Заявка", profile.applicationStatus() + " — подана " + submitted
                    + (profile.applicationDecidedBy() == null ? "" : ", решение принял: " + decided), false);
        }

        embed.addField("История наказаний", formatPunishments(profile.punishments()), false);

        return embed;
    }

    /** Компактный список последних наказаний из БД FlectonePulse (см. {@link com.sunshine.plugin.profile.FlectonePulseHistory}). */
    private String formatPunishments(List<com.sunshine.plugin.profile.FlectonePulseHistory.PunishmentRecord> punishments) {
        if (punishments.isEmpty()) return "нет данных (или FlectonePulse ещё не перенесён на MySQL)";

        StringBuilder sb = new StringBuilder();
        for (var p : punishments) {
            String icon = switch (p.type()) {
                case "BAN" -> "🔨";
                case "MUTE" -> "🔇";
                case "WARN" -> "⚠";
                case "KICK" -> "👢";
                default -> "•";
            };
            String date = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    .format(Instant.ofEpochMilli(p.dateEpochMs()).atZone(java.time.ZoneId.systemDefault()));
            String status = p.active() ? "активно" : "снято/истекло";
            sb.append(icon).append(' ').append(p.type()).append(" — ").append(date)
                    .append(" (").append(status).append("), от ").append(p.moderatorName());
            if (p.reason() != null && !p.reason().isBlank()) sb.append(": ").append(p.reason());
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String formatPlaytime(Long seconds) {
        if (seconds == null) return "неизвестно";
        Duration duration = Duration.ofSeconds(seconds);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return hours + "ч " + minutes + "м";
    }

    private List<ActionRow> actionRows(String nickname, String lastIp) {
        List<ActionRow> rows = new java.util.ArrayList<>(List.of(
                ActionRow.of(
                        Button.of(ButtonStyle.DANGER, BTN_PREFIX + "ban:" + nickname, "Бан"),
                        Button.of(ButtonStyle.DANGER, BTN_PREFIX + "mute:" + nickname, "Мут"),
                        Button.of(ButtonStyle.SECONDARY, BTN_PREFIX + "kick:" + nickname, "Кик"),
                        Button.of(ButtonStyle.SECONDARY, BTN_PREFIX + "warn:" + nickname, "Варн")
                ),
                ActionRow.of(
                        Button.of(ButtonStyle.PRIMARY, BTN_PREFIX + "banlist:" + nickname, "Список банов"),
                        Button.of(ButtonStyle.PRIMARY, BTN_PREFIX + "mutelist:" + nickname, "Список мутов"),
                        Button.of(ButtonStyle.PRIMARY, BTN_PREFIX + "warnlist:" + nickname, "Список варнов")
                )
        ));
        // IP известен только если player-db включён и игрок уже когда-то заходил
        if (lastIp != null && !lastIp.isBlank()) {
            rows.add(ActionRow.of(
                    Button.of(ButtonStyle.PRIMARY, BTN_PREFIX + "geolocate:" + nickname + ":" + lastIp, "Геолокация"),
                    Button.of(ButtonStyle.SECONDARY, BTN_PREFIX + "ip:" + nickname + ":" + lastIp, "IP")
            ));
        }
        return rows;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(BTN_PREFIX)) return;

        if (!hasModeratorRole(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }

        String[] parts = id.substring(BTN_PREFIX.length()).split(":", 2);
        if (parts.length != 2) return;
        String action = parts[0];
        String nickname = parts[1];

        switch (action) {
            case "banlist" -> runTypedHistory(event, nickname, "BAN");
            case "mutelist" -> runTypedHistory(event, nickname, "MUTE");
            case "warnlist" -> runTypedHistory(event, nickname, "WARN");
            case "geolocate" -> {
                // nickname на самом деле хранит "ник:ip" - см. actionRows()
                String[] nickAndIp = nickname.split(":", 2);
                if (nickAndIp.length == 2) runGeolocate(event, nickAndIp[0], nickAndIp[1]);
            }
            case "ip" -> {
                String[] nickAndIp = nickname.split(":", 2);
                if (nickAndIp.length == 2) {
                    event.reply("IP игрока '" + nickAndIp[0] + "': `" + nickAndIp[1] + "`").setEphemeral(true).queue();
                }
            }
            case "ban" -> openPunishModal(event, "ban", nickname, true);
            case "mute" -> openPunishModal(event, "mute", nickname, true);
            case "kick" -> openPunishModal(event, "kick", nickname, false);
            case "warn" -> openPunishModal(event, "warn", nickname, false);
            default -> { }
        }
    }

    /** Список наказаний одного типа - читает fp_moderation напрямую, ответ ephemeral сразу в чат (не через консоль FlectonePulse). */
    private void runTypedHistory(ButtonInteractionEvent event, String nickname, String type) {
        event.deferReply(true).queue();
        UUID uuid = profileService.resolveUuid(nickname);
        if (uuid == null) {
            event.getHook().editOriginal("Игрок '" + nickname + "' не найден.").queue();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var punishments = plugin.flectonePulseHistory().punishmentsOfType(uuid, type, 10);
            event.getHook().editOriginal(formatPunishments(punishments)).queue();
        });
    }

    /** Чистая геолокация через ip-api.com напрямую (в обход console-relay FlectonePulse, где текст нечитаем). */
    private void runGeolocate(ButtonInteractionEvent event, String nickname, String ip) {
        event.deferReply(true).queue();
        plugin.geoLocationService().lookup(ip).whenComplete((geo, throwable) -> {
            String text = (throwable != null || geo == null)
                    ? "Не удалось определить геолокацию для " + nickname + " (" + ip + ")."
                    : nickname + " (" + ip + "): " + geo.format();
            event.getHook().editOriginal(text).queue();
        });
    }

    private void openPunishModal(ButtonInteractionEvent event, String action, String nickname, boolean withDuration) {
        List<net.dv8tion.jda.api.interactions.components.ActionRow> rows = new java.util.ArrayList<>();
        if (withDuration) {
            TextInput duration = TextInput.create(INPUT_DURATION, "Срок (1d2h3m4s, пусто = навсегда)", TextInputStyle.SHORT)
                    .setRequired(false)
                    .build();
            rows.add(ActionRow.of(duration));
        }
        TextInput reason = TextInput.create(INPUT_REASON, "Причина", TextInputStyle.PARAGRAPH)
                .setRequired(false)
                .build();
        rows.add(ActionRow.of(reason));

        Modal modal = Modal.create(MODAL_PREFIX + action + ":" + nickname, actionTitle(action) + ": " + nickname)
                .addComponents(rows)
                .build();
        event.replyModal(modal).queue();
    }

    private String actionTitle(String action) {
        return switch (action) {
            case "ban" -> "Забанить";
            case "mute" -> "Замутить";
            case "kick" -> "Кикнуть";
            case "warn" -> "Предупредить";
            default -> action;
        };
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith(MODAL_PREFIX)) return;

        if (!hasModeratorRole(event.getMember())) {
            event.reply("Недостаточно прав для этого действия.").setEphemeral(true).queue();
            return;
        }

        String[] parts = event.getModalId().substring(MODAL_PREFIX.length()).split(":", 2);
        if (parts.length != 2) return;
        String action = parts[0];
        String nickname = parts[1];

        String duration = valueOrNull(event, INPUT_DURATION);
        String reason = valueOrNull(event, INPUT_REASON);

        ModerationExecutor.Action moderationAction = switch (action) {
            case "ban" -> ModerationExecutor.Action.BAN;
            case "mute" -> ModerationExecutor.Action.MUTE;
            case "kick" -> ModerationExecutor.Action.KICK;
            case "warn" -> ModerationExecutor.Action.WARN;
            default -> null;
        };
        if (moderationAction == null) return;

        String moderatorName = event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getName();
        moderationExecutor.execute(moderationAction, nickname, duration, reason, moderatorName);
        event.reply("Выполнено: " + actionTitle(action) + " → " + nickname
                + (reason == null ? "" : " (" + reason + ")")).setEphemeral(true).queue();
    }

    private String valueOrNull(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        if (mapping == null) return null;
        String value = mapping.getAsString();
        return value.isBlank() ? null : value;
    }
}
