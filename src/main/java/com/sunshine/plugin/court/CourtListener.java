package com.sunshine.plugin.court;

import com.sunshine.plugin.court.CourtModels.CourtCase;
import com.sunshine.plugin.court.CourtModels.PlayerRef;
import com.sunshine.plugin.territory.TerritoryResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.util.List;

import static com.sunshine.plugin.court.CourtService.P;
import static com.sunshine.plugin.court.CourtService.pad;

/**
 * Discord-слой суда: слеш-команды, кнопки и формы. Здесь только разбор взаимодействий и права -
 * вся логика и работа с БД в {@link CourtService}. Всё, что открывает форму (replyModal), делается
 * мгновенно и без обращений к БД - Discord ждёт ответ на клик не дольше 3 секунд. Длина label у
 * TextInput - не более 45 символов (иначе JDA кидает исключение до ответа и Discord показывает
 * "приложение не ответило").
 */
public final class CourtListener extends ListenerAdapter {

    private final CourtService service;

    public CourtListener(CourtService service) {
        this.service = service;
    }

    // ==================== Слеш-команды ====================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "court-panel" -> handlePanelSetup(event);
            case "fine" -> handleFine(event);
            case "decree" -> handleDecree(event);
            case "dossier" -> handleDossier(event);
            case "case" -> handleCaseView(event);
            default -> { }
        }
    }

    private boolean requireReady(SlashCommandInteractionEvent event) {
        if (service.ready()) return true;
        event.reply("Суд сейчас недоступен (нет связи с базой данных).").setEphemeral(true).queue();
        return false;
    }

    private void handlePanelSetup(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("У тебя нет прав для этой команды.").setEphemeral(true).queue();
            return;
        }
        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0xff, 0xcb, 0x6b))
                .setTitle("⚖ Суд Sunshine")
                .setDescription("Здесь решаются споры между игроками.\n\n"
                        + "• **Подать иск** — опиши, что случилось, и приложи доказательства.\n"
                        + "• **Мои дела** — статус твоих исков и дел против тебя.\n"
                        + "• **Мои штрафы** — выставленные тебе штрафы, оплата прямо отсюда.");
        event.getChannel().sendMessageEmbeds(embed.build()).addActionRow(
                Button.success(P + "panel:file", "📜 Подать иск"),
                Button.primary(P + "panel:mycases", "📂 Мои дела"),
                Button.secondary(P + "panel:myfines", "💸 Мои штрафы")).queue();
        event.reply("Панель суда опубликована.").setEphemeral(true).queue();
    }

    private void handleFine(SlashCommandInteractionEvent event) {
        if (!requireReady(event)) return;
        if (!service.isJudge(event.getMember())) {
            event.reply("Выставлять штрафы могут только судьи.").setEphemeral(true).queue();
            return;
        }
        String nick = event.getOption("player").getAsString();
        long amount = event.getOption("amount").getAsLong();
        String reason = event.getOption("reason").getAsString();
        Integer days = event.getOption("days") == null ? null : (int) event.getOption("days").getAsLong();
        String judgeName = event.getUser().getName();
        event.deferReply(true).queue();
        service.async(() -> {
            if (amount > service.settings().fineMaxAmount()) {
                event.getHook().editOriginal("❌ Максимальный штраф — " + service.settings().fineMaxAmount() + ".").queue();
                return;
            }
            PlayerRef target = service.resolvePlayer(nick);
            if (target == null) {
                event.getHook().editOriginal("❌ Игрок '" + nick + "' не найден — он должен был заходить на сервер.").queue();
                return;
            }
            var fine = service.issueFine(null, target, amount, reason, judgeName, days);
            event.getHook().editOriginal("✅ Штраф №" + fine.id() + " выставлен игроку " + target.name() + ".").queue();
        });
    }

    private void handleDecree(SlashCommandInteractionEvent event) {
        if (!requireReady(event)) return;
        if (!service.isJudge(event.getMember())) {
            event.reply("Выносить постановления могут только судьи.").setEphemeral(true).queue();
            return;
        }
        String nick = event.getOption("player").getAsString();
        String type = event.getOption("type").getAsString();
        String reason = event.getOption("reason").getAsString();
        String duration = event.getOption("duration") == null ? null : event.getOption("duration").getAsString();
        String judgeName = event.getUser().getName();
        event.deferReply(true).queue();
        service.async(() -> {
            CourtService.Sanction sanction;
            try {
                sanction = service.parseSanction(type + (duration == null ? "" : " " + duration));
            } catch (IllegalArgumentException e) {
                event.getHook().editOriginal("❌ " + e.getMessage()).queue();
                return;
            }
            PlayerRef target = service.resolvePlayer(nick);
            if (target == null) {
                event.getHook().editOriginal("❌ Игрок '" + nick + "' не найден — он должен был заходить на сервер.").queue();
                return;
            }
            var decree = service.issueDecree(null, target, sanction.type(), sanction.duration(), reason, judgeName);
            event.getHook().editOriginal("✅ Постановление №" + decree.id() + " вынесено и исполнено.").queue();
        });
    }

    private void handleDossier(SlashCommandInteractionEvent event) {
        if (!requireReady(event)) return;
        if (!service.isJudge(event.getMember())) {
            event.reply("Досье доступно только судьям.").setEphemeral(true).queue();
            return;
        }
        String nick = event.getOption("player").getAsString();
        event.deferReply(true).queue();
        service.async(() -> {
            PlayerRef target = service.resolvePlayer(nick);
            if (target == null) {
                event.getHook().editOriginal("❌ Игрок '" + nick + "' не найден.").queue();
                return;
            }
            event.getHook().editOriginalEmbeds(service.dossierEmbed(target).build()).queue();
        });
    }

    private void handleCaseView(SlashCommandInteractionEvent event) {
        if (!requireReady(event)) return;
        int id = (int) event.getOption("id").getAsLong();
        boolean judge = service.isJudge(event.getMember());
        String discordId = event.getUser().getId();
        event.deferReply(true).queue();
        service.async(() -> {
            CourtCase c = service.getCase(id);
            if (c == null || !(judge || service.isPlaintiff(c, discordId) || service.isDefendant(c, discordId))) {
                event.getHook().editOriginal("❌ Дело не найдено или у тебя нет к нему доступа.").queue();
                return;
            }
            event.getHook().editOriginalEmbeds(service.caseEmbed(c).build()).queue();
        });
    }

    // ==================== Кнопки ====================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(P)) return;
        String[] p = id.substring(P.length()).split(":");

        switch (p[0]) {
            case "panel" -> handlePanelButton(event, p[1]);
            case "case" -> handleCaseButton(event, p);
            case "fine" -> handleFineButton(event, p[1], Integer.parseInt(p[2]));
            case "appeal" -> handleAppealDecision(event, p[1], p[2], Integer.parseInt(p[3]));
            default -> { }
        }
    }

    private void handlePanelButton(ButtonInteractionEvent event, String action) {
        switch (action) {
            case "file" -> {
                Modal modal = Modal.create(P + "modal:file", "Подача иска")
                        .addActionRow(TextInput.create("defendant", "Ник игрока, на которого иск", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(16).build())
                        .addActionRow(TextInput.create("title", "Кратко: суть иска", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(100).build())
                        .addActionRow(TextInput.create("claim", "Что произошло", TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(1000).build())
                        .addActionRow(TextInput.create("evidence", "Доказательства (ссылки, скриншоты)", TextInputStyle.PARAGRAPH)
                                .setRequired(false).setMaxLength(1000).build())
                        .build();
                event.replyModal(modal).queue();
            }
            case "mycases" -> {
                if (!ensureReady(event)) return;
                event.deferReply(true).queue();
                service.async(() -> event.getHook().editOriginalEmbeds(service.myCasesEmbed(event.getUser().getId()).build()).queue());
            }
            case "myfines" -> {
                if (!ensureReady(event)) return;
                event.deferReply(true).queue();
                service.async(() -> {
                    CourtService.FinesView view = service.myFines(event.getUser().getId());
                    var edit = event.getHook().editOriginalEmbeds(view.embed().build());
                    if (!view.payButtons().isEmpty()) edit = edit.setComponents(ActionRow.of(view.payButtons()));
                    edit.queue();
                });
            }
            default -> { }
        }
    }

    private boolean ensureReady(ButtonInteractionEvent event) {
        if (service.ready()) return true;
        event.reply("Суд сейчас недоступен (нет связи с базой данных).").setEphemeral(true).queue();
        return false;
    }

    private void handleCaseButton(ButtonInteractionEvent event, String[] p) {
        String action = p[1];

        if (action.equals("appeal")) {
            // кнопка в ЛС ответчику - судейские права не нужны
            int id = Integer.parseInt(p[2]);
            event.replyModal(appealModal("modal:appealcase:" + id)).queue();
            return;
        }
        if (!ensureReady(event)) return;
        if (!service.isJudge(event.getMember())) {
            event.reply("Это действие доступно только судьям.").setEphemeral(true).queue();
            return;
        }

        switch (action) {
            case "accept" -> {
                int id = Integer.parseInt(p[2]);
                Member judge = event.getMember();
                event.deferEdit().queue();
                service.async(() -> {
                    TerritoryResult<CourtCase> result = service.acceptCase(id, event.getGuild(), judge);
                    if (!result.isOk()) {
                        event.getHook().sendMessage("❌ " + result.error()).setEphemeral(true).queue();
                        return;
                    }
                    event.getHook().editOriginalEmbeds(service.caseEmbed(result.value())
                            .setFooter("Принял судья " + judge.getEffectiveName()).build()).setComponents(List.<ActionRow>of()).queue();
                });
            }
            case "reject" -> {
                int id = Integer.parseInt(p[2]);
                event.replyModal(Modal.create(P + "modal:reject:" + id, "Отклонение иска")
                        .addActionRow(TextInput.create("reason", "Причина отказа", TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(500).build()).build()).queue();
            }
            case "hearing" -> {
                int id = Integer.parseInt(p[2]);
                event.replyModal(Modal.create(P + "modal:hearing:" + id, "Назначение заседания")
                        .addActionRow(TextInput.create("when", "Дата и время (дд.мм.гггг чч:мм)", TextInputStyle.SHORT)
                                .setRequired(true).setPlaceholder("25.10.2026 19:30").setMaxLength(16).build()).build()).queue();
            }
            case "start" -> {
                int id = Integer.parseInt(p[2]);
                event.deferReply(true).queue();
                service.async(() -> {
                    TerritoryResult<CourtCase> result = service.startHearing(id);
                    event.getHook().editOriginal(result.isOk() ? "✅ Заседание начато." : "❌ " + result.error()).queue();
                });
            }
            case "verdict" -> {
                String type = p[2];
                int id = Integer.parseInt(p[3]);
                Modal.Builder modal = Modal.create(P + "modal:verdict:" + type + ":" + id, "Вердикт: " + verdictTitle(type))
                        .addActionRow(TextInput.create("text", "Обоснование решения", TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(1000).build());
                if (type.equals("guilty")) {
                    modal.addActionRow(TextInput.create("fine", "Штраф (число, можно пусто)", TextInputStyle.SHORT)
                            .setRequired(false).setMaxLength(9).build());
                    modal.addActionRow(TextInput.create("sanction", "Санкция: ban 30d, mute 7d, kick, warn", TextInputStyle.SHORT)
                            .setRequired(false).setMaxLength(30).build());
                }
                event.replyModal(modal.build()).queue();
            }
            case "close" -> {
                int id = Integer.parseInt(p[2]);
                String judgeName = event.getUser().getName();
                event.deferReply(true).queue();
                service.async(() -> {
                    TerritoryResult<CourtCase> result = service.closeCase(id, judgeName);
                    event.getHook().editOriginal(result.isOk() ? "✅ Дело закрыто." : "❌ " + result.error()).queue();
                });
            }
            default -> { }
        }
    }

    private static String verdictTitle(String type) {
        return switch (type) {
            case "guilty" -> "виновен";
            case "acquit" -> "оправдан";
            default -> "мировое";
        };
    }

    private void handleFineButton(ButtonInteractionEvent event, String action, int id) {
        if (action.equals("appeal")) {
            event.replyModal(appealModal("modal:appealfine:" + id)).queue();
            return;
        }
        if (!action.equals("pay") || !ensureReady(event)) return;
        String discordId = event.getUser().getId();
        event.deferReply(true).queue();
        service.async(() -> {
            var result = service.payFine(id, discordId);
            event.getHook().editOriginal(result.isOk()
                    ? "✅ Штраф №" + id + " оплачен: списано " + result.value().amount() + " " + service.settings().currencyName() + "."
                    : "❌ " + result.error()).queue();
        });
    }

    private Modal appealModal(String idSuffix) {
        return Modal.create(P + idSuffix, "Апелляция")
                .addActionRow(TextInput.create("reason", "Почему решение неверное", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(1000).build()).build();
    }

    private void handleAppealDecision(ButtonInteractionEvent event, String decision, String kind, int id) {
        if (!ensureReady(event)) return;
        if (!service.isChief(event.getMember())) {
            event.reply("Апелляции рассматривают только главные судьи.").setEphemeral(true).queue();
            return;
        }
        boolean overturn = decision.equals("overturn");
        String judgeName = event.getUser().getName();
        event.deferEdit().queue();
        service.async(() -> {
            TerritoryResult<String> result = service.decideAppeal(kind, id, overturn, judgeName);
            if (!result.isOk()) {
                event.getHook().sendMessage("❌ " + result.error()).setEphemeral(true).queue();
                return;
            }
            event.getHook().editOriginalEmbeds(new EmbedBuilder(event.getMessage().getEmbeds().get(0))
                            .setFooter("Рассмотрел " + judgeName + ": " + result.value()).build())
                    .setComponents(List.<ActionRow>of()).queue();
        });
    }

    // ==================== Формы ====================

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(P + "modal:")) return;
        String[] p = id.substring((P + "modal:").length()).split(":");

        if (!service.ready()) {
            event.reply("Суд сейчас недоступен (нет связи с базой данных).").setEphemeral(true).queue();
            return;
        }
        switch (p[0]) {
            case "file" -> handleFileSubmit(event);
            case "reject" -> handleRejectSubmit(event, Integer.parseInt(p[1]));
            case "hearing" -> handleHearingSubmit(event, Integer.parseInt(p[1]));
            case "verdict" -> handleVerdictSubmit(event, p[1], Integer.parseInt(p[2]));
            case "appealcase" -> handleAppealSubmit(event, "case", Integer.parseInt(p[1]));
            case "appealfine" -> handleAppealSubmit(event, "fine", Integer.parseInt(p[1]));
            default -> { }
        }
    }

    private String value(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString();
    }

    private void handleFileSubmit(ModalInteractionEvent event) {
        String defendant = value(event, "defendant");
        String title = value(event, "title");
        String claim = value(event, "claim");
        String evidence = value(event, "evidence");
        String discordId = event.getUser().getId();
        event.deferReply(true).queue();
        service.async(() -> {
            TerritoryResult<Integer> result = service.fileCase(discordId, defendant, title, claim, evidence);
            event.getHook().editOriginal(result.isOk()
                    ? "✅ Иск №" + pad(result.value()) + " подан. Судьи рассмотрят его, ты получишь уведомление в личные сообщения."
                    : "❌ " + result.error()).queue();
        });
    }

    private void handleRejectSubmit(ModalInteractionEvent event, int caseId) {
        if (!judgeOrDeny(event)) return;
        String reason = value(event, "reason");
        String judgeName = event.getUser().getName();
        event.deferEdit().queue();
        service.async(() -> {
            TerritoryResult<CourtCase> result = service.rejectCase(caseId, judgeName, reason);
            if (!result.isOk()) {
                event.getHook().sendMessage("❌ " + result.error()).setEphemeral(true).queue();
                return;
            }
            event.getHook().editOriginalEmbeds(service.caseEmbed(result.value())
                    .setFooter("Отклонил " + judgeName + ": " + reason).build()).setComponents(List.<ActionRow>of()).queue();
        });
    }

    private void handleHearingSubmit(ModalInteractionEvent event, int caseId) {
        if (!judgeOrDeny(event)) return;
        String when = value(event, "when");
        String judgeName = event.getUser().getName();
        event.deferReply(true).queue();
        service.async(() -> {
            TerritoryResult<Long> time = service.parseHearingTime(when);
            if (!time.isOk()) {
                event.getHook().editOriginal("❌ " + time.error()).queue();
                return;
            }
            TerritoryResult<CourtCase> result = service.scheduleHearing(caseId, time.value(), judgeName);
            event.getHook().editOriginal(result.isOk() ? "✅ Заседание назначено, участники уведомлены." : "❌ " + result.error()).queue();
        });
    }

    private void handleVerdictSubmit(ModalInteractionEvent event, String type, int caseId) {
        if (!judgeOrDeny(event)) return;
        String verdict = switch (type) {
            case "guilty" -> "GUILTY";
            case "acquit" -> "ACQUITTED";
            default -> "SETTLED";
        };
        String text = value(event, "text");
        String fineRaw = value(event, "fine").trim();
        String sanction = value(event, "sanction");
        String judgeName = event.getUser().getName();

        long fine = 0;
        if (!fineRaw.isEmpty()) {
            try {
                fine = Long.parseLong(fineRaw);
            } catch (NumberFormatException e) {
                event.reply("❌ Штраф должен быть целым числом.").setEphemeral(true).queue();
                return;
            }
        }
        long fineAmount = fine;
        event.deferReply(true).queue();
        service.async(() -> {
            TerritoryResult<CourtCase> result = service.issueVerdict(caseId, verdict, text, fineAmount, sanction, judgeName);
            event.getHook().editOriginal(result.isOk() ? "✅ Вердикт вынесен и объявлен." : "❌ " + result.error()).queue();
        });
    }

    private void handleAppealSubmit(ModalInteractionEvent event, String kind, int id) {
        String reason = value(event, "reason");
        String discordId = event.getUser().getId();
        event.deferReply(true).queue();
        service.async(() -> {
            TerritoryResult<Void> result = service.fileAppeal(kind, id, discordId, reason);
            event.getHook().editOriginal(result.isOk()
                    ? "✅ Апелляция подана. Её рассмотрят главные судьи, ты получишь уведомление."
                    : "❌ " + result.error()).queue();
        });
    }

    private boolean judgeOrDeny(ModalInteractionEvent event) {
        if (service.isJudge(event.getMember())) return true;
        event.reply("Это действие доступно только судьям.").setEphemeral(true).queue();
        return false;
    }
}
