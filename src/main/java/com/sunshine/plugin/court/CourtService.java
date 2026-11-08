package com.sunshine.plugin.court;

import com.sunshine.plugin.SunShinePlugin;
import com.sunshine.plugin.court.CourtModels.CourtCase;
import com.sunshine.plugin.court.CourtModels.Decree;
import com.sunshine.plugin.court.CourtModels.Fine;
import com.sunshine.plugin.court.CourtModels.PlayerRef;
import com.sunshine.plugin.profile.ModerationExecutor;
import com.sunshine.plugin.territory.TerritoryResult;
import me.clip.placeholderapi.PlaceholderAPI;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Вся логика суда: иски и дела, заседания, вердикты, штрафы (инвойсы) с оплатой монетами, постановления
 * командами сервера, апелляции, досье. Блокирующая работа (БД, команды сервера) идёт на одном
 * фоновом потоке {@link #async}, Discord-слой только собирает интерфейс.
 */
public final class CourtService implements Listener {

    public static final String P = "sunshine:court:";

    private static final Pattern DURATION = Pattern.compile("^(\\d+[smhdwy])+$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter TRANSCRIPT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Color GOLD = new Color(0xff, 0xcb, 0x6b);
    private static final Color RED = new Color(0xed, 0x42, 0x45);
    private static final Color GREEN = new Color(0x57, 0xf2, 0x87);
    private static final Color BLUE = new Color(0x58, 0x65, 0xf2);
    private static final Color GRAY = new Color(0x99, 0xaa, 0xb5);

    private final SunShinePlugin plugin;
    private final CourtSettings settings;
    private final CourtBot bot;
    private final ModerationExecutor moderation;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SunShine-Court");
        t.setDaemon(true);
        return t;
    });
    private volatile CourtDatabase db;
    private BukkitTask ticker;
    private BukkitTask roleTagTicker;
    /** Кэш "кто судья" по UUID игрока: плейсхолдеры читают его синхронно, а роли тянутся из Discord фоном (при входе и раз в 5 минут). */
    private final java.util.Map<UUID, RoleTag> roleTags = new java.util.concurrent.ConcurrentHashMap<>();

    public enum RoleTag { NONE, JUDGE, CHIEF }

    public CourtService(SunShinePlugin plugin, CourtSettings settings, CourtBot bot) {
        this.plugin = plugin;
        this.settings = settings;
        this.bot = bot;
        this.moderation = new ModerationExecutor(plugin);
    }

    public CourtSettings settings() {
        return settings;
    }

    // ==================== Жизненный цикл ====================

    public void start() {
        async(() -> {
            try {
                CourtDatabase database = new CourtDatabase(plugin);
                database.connect();
                db = database;
            } catch (Exception e) {
                plugin.getLogger().warning("Court: не удалось подключиться к БД, суд отключён: " + e.getMessage());
            }
        });
        ticker = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> async(this::tick), 1200L, 1200L);
        roleTagTicker = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> async(this::refreshAllRoleTags), 600L, 6000L);
    }

    public RoleTag roleTagFor(UUID uuid) {
        return roleTags.getOrDefault(uuid, RoleTag.NONE);
    }

    /** Роли игрока в Discord - судья/главный судья по court.judge-role-ids и court.chief-judge-role-ids; null = Discord ещё не готов. */
    private RoleTag resolveRoleTag(UUID uuid) {
        if (!bot.isReady()) return null;
        String discordId = plugin.linkManager().discordIdFor(uuid);
        if (discordId == null) return RoleTag.NONE;
        List<String> chief = settings.chiefRoleIds();
        List<String> judge = settings.judgeRoleIds();
        for (Guild guild : jda().getGuilds()) {
            try {
                Member member = guild.retrieveMemberById(discordId).complete();
                if (member.getRoles().stream().anyMatch(r -> chief.contains(r.getId()))) return RoleTag.CHIEF;
                if (member.getRoles().stream().anyMatch(r -> judge.contains(r.getId()))) return RoleTag.JUDGE;
                return RoleTag.NONE;
            } catch (Exception ignored) {
                // не участник этого сервера - пробуем следующий
            }
        }
        return RoleTag.NONE;
    }

    private void refreshRoleTag(UUID uuid) {
        if (!settings.tagsEnabled()) return;
        RoleTag tag = resolveRoleTag(uuid);
        if (tag != null) roleTags.put(uuid, tag);
    }

    private void refreshAllRoleTags() {
        if (!settings.tagsEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) refreshRoleTag(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        roleTags.remove(event.getPlayer().getUniqueId());
    }

    /** Фоновая задача, которой разрешено бросать проверяемые исключения (БД, команды сервера). */
    @FunctionalInterface
    public interface Task {
        void run() throws Exception;
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (roleTagTicker != null) {
            roleTagTicker.cancel();
            roleTagTicker = null;
        }
        worker.shutdown();
        if (db != null) db.close();
    }

    public void async(Task task) {
        worker.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Court: ошибка в фоновой задаче", t);
            }
        });
    }

    public boolean ready() {
        return db != null && bot.isReady();
    }

    private CourtDatabase db() {
        CourtDatabase d = db;
        if (d == null) throw new IllegalStateException("База суда недоступна");
        return d;
    }

    private JDA jda() {
        return bot.jda();
    }

    private <T> TerritoryResult<T> internal(Exception e) {
        plugin.getLogger().log(Level.WARNING, "Court: внутренняя ошибка", e);
        return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
    }

    private <T> T onMain(Callable<T> call) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(plugin, call).get(15, TimeUnit.SECONDS);
    }

    // ==================== Права и игроки ====================

    public boolean isJudge(Member member) {
        return member != null && (isChief(member) || hasAnyRole(member, settings.judgeRoleIds()));
    }

    public boolean isChief(Member member) {
        return member != null && (member.hasPermission(Permission.ADMINISTRATOR) || hasAnyRole(member, settings.chiefRoleIds()));
    }

    private boolean hasAnyRole(Member member, List<String> ids) {
        return member.getRoles().stream().anyMatch(r -> ids.contains(r.getId()));
    }

    public PlayerRef resolvePlayer(String nick) {
        String name = nick == null ? "" : nick.trim();
        if (name.isEmpty()) return null;
        UUID uuid = plugin.linkManager().uuidForName(name);
        String resolvedName = uuid == null ? null : plugin.linkManager().nameFor(uuid);
        if (uuid == null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
            if (offline == null) return null;
            uuid = offline.getUniqueId();
            resolvedName = offline.getName() == null ? name : offline.getName();
        }
        return new PlayerRef(uuid, resolvedName, plugin.linkManager().discordIdFor(uuid));
    }

    public List<UUID> uuidsOf(String discordId) {
        return plugin.linkManager().uuidsForDiscordId(discordId);
    }

    public boolean isDefendant(CourtCase c, String discordId) {
        return discordId.equals(c.defendantDiscord()) || uuidsOf(discordId).contains(UUID.fromString(c.defendantUuid()));
    }

    public boolean isPlaintiff(CourtCase c, String discordId) {
        return discordId.equals(c.plaintiffDiscord());
    }

    public boolean isFineTarget(Fine f, String discordId) {
        return discordId.equals(f.targetDiscord()) || uuidsOf(discordId).contains(UUID.fromString(f.targetUuid()));
    }

    // ==================== Дела ====================

    public TerritoryResult<Integer> fileCase(String plaintiffDiscord, String defendantNick, String title, String claim, String evidence) {
        try {
            List<UUID> mine = uuidsOf(plaintiffDiscord);
            if (mine.isEmpty()) return TerritoryResult.fail("Сначала привяжи Minecraft-аккаунт к Discord (команда /link в игре).");
            PlayerRef defendant = resolvePlayer(defendantNick);
            if (defendant == null) return TerritoryResult.fail("Игрок '" + defendantNick.trim() + "' не найден - он должен был заходить на сервер.");
            if (mine.contains(defendant.uuid())) return TerritoryResult.fail("Нельзя подать иск на самого себя.");
            if (db().countOpenCases(plaintiffDiscord) >= settings.maxOpenCasesPerPlayer()) {
                return TerritoryResult.fail("У тебя уже " + settings.maxOpenCasesPerPlayer() + " незакрытых дела - дождись решения по ним.");
            }
            String plaintiffName = plugin.linkManager().nameFor(mine.get(0));
            int id = db().insertCase(plaintiffDiscord, plaintiffName == null ? plaintiffDiscord : plaintiffName,
                    defendant.uuid().toString(), defendant.name(), defendant.discordId(),
                    cut(title, 100), claim, evidence == null || evidence.isBlank() ? null : evidence);
            postToInbox(db().getCase(id));
            return TerritoryResult.ok(id);
        } catch (Exception e) {
            return internal(e);
        }
    }

    private void postToInbox(CourtCase c) {
        TextChannel inbox = textChannel(settings.inboxChannelId());
        if (inbox == null) {
            plugin.getLogger().warning("Court: канал court.channels.inbox не найден - иск №" + c.id() + " не показан судьям.");
            return;
        }
        var action = inbox.sendMessageEmbeds(caseEmbed(c).setFooter("Новый иск - примите или отклоните").build())
                .addActionRow(Button.success(P + "case:accept:" + c.id(), "✅ Принять"),
                        Button.danger(P + "case:reject:" + c.id(), "❌ Отклонить"));
        String ping = roleMentions();
        if (!ping.isBlank()) action = action.setContent(ping);
        action.queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить иск в inbox: " + t.getMessage()));
    }

    public TerritoryResult<CourtCase> acceptCase(int id, Guild guild, Member judge) {
        try {
            if (!db().transitionCase(id, "ACCEPTED", "FILED")) return TerritoryResult.fail("Иск уже рассмотрен.");
            CourtCase c = db().getCase(id);
            TextChannel room = createCaseRoom(guild, c, judge.getId());
            db().assignJudge(id, judge.getId(), room == null ? null : room.getId());
            c = db().getCase(id);

            if (room != null) {
                room.sendMessageEmbeds(caseEmbed(c).setFooter("Кнопки ниже доступны судье").build())
                        .setContent(partyMentions(c))
                        .addComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(judgeRow1(id)),
                                net.dv8tion.jda.api.interactions.components.ActionRow.of(judgeRow2(id)))
                        .queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить карточку в комнату: " + t.getMessage()));
            }
            announce(partyMentions(c), new EmbedBuilder().setColor(BLUE)
                    .setTitle("📥 Иск принят к рассмотрению — дело №" + pad(id))
                    .setDescription(cut(c.title(), 300))
                    .addField("Истец", mention(c.plaintiffDiscord(), c.plaintiffName()), true)
                    .addField("Ответчик", mention(c.defendantDiscord(), c.defendantName()), true)
                    .addField("Судья", "<@" + judge.getId() + ">", true).build());
            dmBoth(c, new EmbedBuilder().setColor(BLUE).setTitle("Дело №" + pad(id) + " принято")
                    .setDescription("Иск принят судьёй " + judge.getAsMention() + (room == null ? "" : ". Комната дела: " + room.getAsMention()))
                    .build());
            return TerritoryResult.ok(c);
        } catch (Exception e) {
            return internal(e);
        }
    }

    public TerritoryResult<CourtCase> rejectCase(int id, String judgeName, String reason) {
        try {
            if (!db().transitionCase(id, "REJECTED", "FILED")) return TerritoryResult.fail("Иск уже рассмотрен.");
            CourtCase c = db().getCase(id);
            dm(c.plaintiffDiscord(), new EmbedBuilder().setColor(RED).setTitle("Иск №" + pad(id) + " отклонён")
                    .setDescription("Судья: " + judgeName).addField("Причина", cut(reason, 1000), false).build(), null);
            audit("❌ Иск №" + pad(id) + " отклонён (" + judgeName + "): " + cut(reason, 300));
            return TerritoryResult.ok(c);
        } catch (Exception e) {
            return internal(e);
        }
    }

    /** Время вводится в часовом поясе court.timezone, формат "дд.мм.гггг чч:мм". */
    public TerritoryResult<Long> parseHearingTime(String input) {
        try {
            long at = LocalDateTime.parse(input.trim(), INPUT_TIME).atZone(settings.timezone()).toInstant().toEpochMilli();
            if (at <= System.currentTimeMillis()) return TerritoryResult.fail("Это время уже прошло.");
            return TerritoryResult.ok(at);
        } catch (Exception e) {
            return TerritoryResult.fail("Не понял дату. Формат: `дд.мм.гггг чч:мм`, например `25.10.2026 19:30` (время " + settings.timezone() + ").");
        }
    }

    public TerritoryResult<CourtCase> scheduleHearing(int id, long at, String judgeName) {
        try {
            if (!db().transitionCase(id, "SCHEDULED", "ACCEPTED", "SCHEDULED")) {
                return TerritoryResult.fail("Заседание можно назначить только по принятому делу, которое ещё не слушается.");
            }
            db().setHearing(id, at);
            CourtCase c = db().getCase(id);
            long sec = at / 1000;
            MessageEmbed embed = new EmbedBuilder().setColor(GOLD).setTitle("📅 Назначено заседание — дело №" + pad(id))
                    .setDescription(cut(c.title(), 300))
                    .addField("Начало", "<t:" + sec + ":f> (<t:" + sec + ":R>)", false)
                    .addField("Судья", judgeName, true).build();
            announce(partyMentions(c), embed);
            room(c, null, embed, null);
            dmBoth(c, embed);
            return TerritoryResult.ok(c);
        } catch (Exception e) {
            return internal(e);
        }
    }

    /** Начинает заседание (вручную кнопкой или по расписанию из {@link #tick}) и объявляет об этом. */
    public TerritoryResult<CourtCase> startHearing(int id) {
        try {
            if (!db().transitionCase(id, "HEARING", "ACCEPTED", "SCHEDULED")) {
                return TerritoryResult.fail("Заседание уже идёт или дело закрыто.");
            }
            CourtCase c = db().getCase(id);
            MessageEmbed embed = new EmbedBuilder().setColor(GOLD).setTitle("⚖ Началось заседание — дело №" + pad(id))
                    .setDescription(cut(c.title(), 300))
                    .addField("Истец", mention(c.plaintiffDiscord(), c.plaintiffName()), true)
                    .addField("Ответчик", mention(c.defendantDiscord(), c.defendantName()), true)
                    .addField("Судья", c.judgeDiscord() == null ? "-" : "<@" + c.judgeDiscord() + ">", true)
                    .setFooter("Просьба высказаться в комнате дела").build();
            announce(partyMentions(c), embed);
            room(c, partyMentions(c), embed, null);
            return TerritoryResult.ok(c);
        } catch (Exception e) {
            return internal(e);
        }
    }

    public record Sanction(String type, String duration) {
    }

    /** "ban 30d", "mute 7d", "kick", "warn" (и русские "бан/мут/кик/варн"). Пусто = без санкции. */
    public Sanction parseSanction(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().toLowerCase().split("\\s+");
        String type = switch (parts[0]) {
            case "ban", "бан" -> "BAN";
            case "mute", "мут" -> "MUTE";
            case "kick", "кик" -> "KICK";
            case "warn", "варн" -> "WARN";
            default -> throw new IllegalArgumentException("Неизвестная санкция '" + parts[0] + "'. Допустимо: ban, mute, kick, warn.");
        };
        String duration = null;
        if (parts.length > 1) {
            if (!DURATION.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("Срок указан неверно: '" + parts[1] + "'. Примеры: 30m, 12h, 7d, 1d12h.");
            }
            duration = parts[1];
        }
        if ((type.equals("KICK") || type.equals("WARN")) && duration != null) duration = null;
        return new Sanction(type, duration);
    }

    public TerritoryResult<CourtCase> issueVerdict(int id, String verdict, String text, long fineAmount, String sanctionSpec, String judgeName) {
        Sanction sanction;
        try {
            sanction = verdict.equals("GUILTY") ? parseSanction(sanctionSpec) : null;
        } catch (IllegalArgumentException e) {
            return TerritoryResult.fail(e.getMessage());
        }
        if (verdict.equals("GUILTY") && (fineAmount < 0 || fineAmount > settings.fineMaxAmount())) {
            return TerritoryResult.fail("Штраф должен быть от 0 до " + settings.fineMaxAmount() + ".");
        }
        try {
            if (!db().transitionCase(id, "VERDICT", "ACCEPTED", "SCHEDULED", "HEARING")) {
                return TerritoryResult.fail("Вердикт можно вынести только по принятому делу без вердикта.");
            }
            db().setVerdict(id, verdict, text);
            CourtCase c = db().getCase(id);
            // Подписывает и числится судьёй тот, кто принял дело, а не тот, кто нажал кнопку (например, главный судья или админ).
            String signer = judgeDisplayName(c.judgeDiscord(), judgeName);
            PlayerRef target = new PlayerRef(UUID.fromString(c.defendantUuid()), c.defendantName(), c.defendantDiscord());

            StringBuilder sanctions = new StringBuilder();
            List<String> docSanctions = new ArrayList<>();
            if (verdict.equals("GUILTY")) {
                if (fineAmount > 0) {
                    Fine fine = issueFine(id, target, fineAmount, "Дело №" + pad(id) + ": " + cut(text, 200), signer, null);
                    sanctions.append("💸 Штраф №").append(fine.id()).append(": ").append(fineAmount).append(' ').append(settings.currencyName()).append('\n');
                    docSanctions.add("• Штраф №" + fine.id() + ": " + fineAmount + " " + settings.currencyName()
                            + ", срок оплаты до " + formatDate(fine.deadline()));
                }
                if (sanction != null) {
                    Decree decree = issueDecree(id, target, sanction.type(), sanction.duration(), "Дело №" + pad(id) + ": " + cut(text, 200), signer);
                    sanctions.append("🔨 ").append(decreeLabel(decree)).append('\n');
                    docSanctions.add("• Постановление №" + decree.id() + ": " + decreeLabel(decree));
                }
                if (sanctions.length() == 0) {
                    sanctions.append("Без санкций.");
                    docSanctions.add("• Без санкций.");
                }
            }

            long appealUntilMillis = System.currentTimeMillis() + settings.appealWindowHours() * 3_600_000L;
            long appealUntil = appealUntilMillis / 1000;
            EmbedBuilder result = new EmbedBuilder().setColor(verdict.equals("GUILTY") ? RED : GREEN)
                    .setTitle("⚖ Итоги по делу №" + pad(id))
                    .setDescription(cut(c.title(), 300))
                    .addField("Решение", verdictLabel(verdict), true)
                    .addField("Судья", signer, true)
                    .addField("Обоснование", cut(text, 1000), false);
            if (sanctions.length() > 0) result.addField("Санкции", cut(sanctions.toString(), 1000), false);
            if (verdict.equals("GUILTY")) result.addField("Обжалование", "до <t:" + appealUntil + ":f>", false);
            List<Button> appealButton = verdict.equals("GUILTY")
                    ? List.of(Button.secondary(P + "case:appeal:" + id, "📨 Обжаловать")) : null;

            // Итоги - готовым документом-картинкой; если нарисовать не вышло (нет шрифтов/AWT на хосте), обычный embed.
            byte[] document = settings.documentEnabled()
                    ? renderVerdictDocument(c, verdict, text, signer, docSanctions, appealUntilMillis) : null;
            if (document != null) {
                String fileName = "delo-" + pad(id) + "-reshenie.png";
                String caption = "⚖ Итоги по делу №" + pad(id);
                announceImage(partyMentions(c) + " " + caption, document, fileName);
                roomImage(c, document, fileName);
                dmImage(c.plaintiffDiscord(), caption, document, fileName, null);
                dmImage(c.defendantDiscord(), caption, document, fileName, appealButton);
            } else {
                announce(partyMentions(c), result.build());
                room(c, null, result.build(), null);
                dm(c.plaintiffDiscord(), result.build(), null);
                dm(c.defendantDiscord(), result.build(), appealButton);
            }
            deliverArchive(db().getCase(id));
            return TerritoryResult.ok(db().getCase(id));
        } catch (Exception e) {
            return internal(e);
        }
    }

    /** Книга-архив дела судье в игре сразу после вердикта; если судьи нет на сервере, её выдадут при входе ({@link #onJoin}). */
    private void deliverArchive(CourtCase c) {
        try {
            if (c.judgeDiscord() == null) return;
            for (UUID uuid : uuidsOf(c.judgeDiscord())) {
                Player judge = Bukkit.getPlayer(uuid);
                if (judge != null) {
                    giveArchive(judge, c);
                    return;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Court: не удалось выдать судье книгу-архив дела №" + pad(c.id()), e);
        }
    }

    private void giveArchive(Player judge, CourtCase c) throws Exception {
        ItemStack book = buildArchiveBook(db().getCase(c.id()));
        onMain(() -> {
            for (ItemStack rest : judge.getInventory().addItem(book).values()) {
                judge.getWorld().dropItem(judge.getLocation(), rest);
            }
            judge.sendMessage("§6⚖ Тебе выдан архив дела №" + pad(c.id()) + " (книга в инвентаре).");
            return null;
        });
        db().markArchiveGiven(c.id());
    }

    /** Написанная книга "Архив суда": стороны, судья, суть иска, решение с обоснованием и санкции. */
    private ItemStack buildArchiveBook(CourtCase c) throws Exception {
        List<String> pages = new ArrayList<>();
        long now = System.currentTimeMillis();
        String judge = judgeDisplayName(c.judgeDiscord(), "-");
        boolean guilty = "GUILTY".equals(c.verdict());

        // Обложка
        pages.add("§l§nАРХИВ СУДА§r\n§0Игровой сервер Sunshine\n\n§lДело №" + pad(c.id()) + "§r§0\n"
                + bookText(cut(c.title(), 90)) + "\n\nСтатус: " + plainStatus(c.status())
                + "\nРешение: " + (c.verdict() == null ? "не вынесено" : verdictLabel(c.verdict())));

        // Участники и хронология
        StringBuilder people = new StringBuilder();
        people.append("Истец: ").append(c.plaintiffName()).append('\n');
        people.append("Ответчик: ").append(c.defendantName()).append('\n');
        people.append("Судья: ").append(judge).append("\n\n");
        people.append("Иск подан: ").append(formatDate(c.createdAt())).append('\n');
        people.append("Заседание: ").append(c.hearingAt() == null ? "не назначалось" : formatDate(c.hearingAt())).append('\n');
        if (c.verdictAt() != null) people.append("Решение: ").append(formatDate(c.verdictAt())).append('\n');
        if (guilty && c.verdictAt() != null) {
            people.append("Обжалование до: ").append(formatDate(c.verdictAt() + settings.appealWindowHours() * 3_600_000L)).append('\n');
        }
        addBookSection(pages, "Участники и сроки", people.toString());

        // Суть дела
        addBookSection(pages, "Предмет иска", bookText(c.title()));
        addBookSection(pages, "Суть иска", bookText(cut(c.claim(), 1500)));
        if (c.evidence() != null && !c.evidence().isBlank()) addBookSection(pages, "Доказательства", bookText(cut(c.evidence(), 800)));

        // Решение и обоснование (причина)
        if (c.verdict() != null) {
            String decision = switch (c.verdict()) {
                case "GUILTY" -> "Признать ответчика виновным.";
                case "ACQUITTED" -> "Ответчика оправдать.";
                case "OVERTURNED" -> "Решение отменено по апелляции.";
                default -> "Утвердить мировое соглашение сторон.";
            };
            addBookSection(pages, "РЕШЕНИЕ", decision);
        }
        if (c.verdictText() != null) addBookSection(pages, "Обоснование и причина", bookText(cut(c.verdictText(), 1500)));

        // Санкции подробно: суммы, сроки, статусы, причины, кто вынес
        StringBuilder sanctions = new StringBuilder();
        for (Fine f : db().finesForCase(c.id())) {
            sanctions.append("Штраф №").append(f.id()).append('\n')
                    .append("Сумма: ").append(f.amount()).append(' ').append(settings.currencyName()).append('\n')
                    .append("Срок оплаты: ").append(formatDate(f.deadline())).append('\n')
                    .append("Статус: ").append(plainFineStatus(f.status())).append('\n')
                    .append("Причина: ").append(bookText(cut(f.reason(), 300))).append('\n')
                    .append("Выставил: ").append(f.issuedBy()).append("\n\n");
        }
        for (Decree d : db().decreesForCase(c.id())) {
            sanctions.append("Постановление №").append(d.id()).append('\n')
                    .append("Мера: ").append(decreeLabel(d)).append('\n')
                    .append("Статус: ").append(d.status().equals("ACTIVE") ? "действует" : "отменено").append('\n')
                    .append("Причина: ").append(bookText(cut(d.reason(), 300))).append('\n')
                    .append("Вынес: ").append(d.issuedBy()).append("\n\n");
        }
        if (sanctions.length() > 0) addBookSection(pages, "Санкции", sanctions.toString());
        else if (c.verdict() != null) addBookSection(pages, "Санкции", "Санкции не назначены.");

        if (c.appeal() != null) addBookSection(pages, "Апелляция", "Статус: " + appealLabel(c.appeal()));

        // Заверка
        pages.add("§0§lДОКУМЕНТ ЗАВЕРЕН§r§0\nСуд Sunshine\n\nДело №" + pad(c.id()) + "\nВыдан судье: " + judge
                + "\nДата выдачи: " + formatDate(now) + "\n\n§8Архивный экземпляр. Оригинал решения опубликован в Discord.");

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Дело №" + pad(c.id()));
        meta.setAuthor("Суд Sunshine");
        for (String page : pages) meta.addPage(page);
        book.setItemMeta(meta);
        return book;
    }

    /** Текст пользователя для книги: без эмодзи и прочих символов вне BMP (в шрифте книги они рисуются квадратами). */
    private static String bookText(String text) {
        return text == null ? "" : text.replaceAll("[\\x{10000}-\\x{10FFFF}]", "").trim();
    }

    private static String plainStatus(String status) {
        return switch (status) {
            case "FILED" -> "иск подан";
            case "ACCEPTED" -> "принят";
            case "SCHEDULED" -> "назначено заседание";
            case "HEARING" -> "идёт заседание";
            case "VERDICT" -> "вынесено решение";
            case "CLOSED" -> "закрыто";
            case "REJECTED" -> "отклонён";
            default -> status;
        };
    }

    private static String plainFineStatus(String status) {
        return switch (status) {
            case "PENDING" -> "ожидает оплаты";
            case "PROCESSING" -> "обрабатывается";
            case "PAID" -> "оплачен";
            case "OVERDUE" -> "просрочен";
            case "CANCELLED" -> "отменён";
            default -> status;
        };
    }

    /** Раскладывает текст раздела по страницам книги (около 220 символов на страницу). */
    private static void addBookSection(List<String> pages, String heading, String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("(?<=\\s)")) {
            if (current.length() + word.length() > 220 && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(word);
        }
        if (current.length() > 0) chunks.add(current.toString());
        for (int i = 0; i < chunks.size(); i++) {
            pages.add((i == 0 ? "§l" + heading + "§r§0\n" : "§0") + chunks.get(i).trim());
        }
    }

    /** Заполняет бланк решения данными дела и рисует его картинкой. null при любом сбое (в т.ч. Error от AWT на хосте без шрифтов). */
    private byte[] renderVerdictDocument(CourtCase c, String verdict, String text, String judgeName,
                                         List<String> sanctionLines, long appealUntilMillis) {
        try {
            List<CourtDocumentRenderer.Field> fields = new ArrayList<>();
            fields.add(new CourtDocumentRenderer.Field("Истец:", c.plaintiffName(), 2));
            fields.add(new CourtDocumentRenderer.Field("Ответчик:", c.defendantName(), 2));
            fields.add(new CourtDocumentRenderer.Field("Предмет иска:", c.title(), 3));
            fields.add(new CourtDocumentRenderer.Field("Суть иска:", c.claim(), 7));
            if (c.hearingAt() != null) fields.add(new CourtDocumentRenderer.Field("Заседание:", formatDate(c.hearingAt()), 1));
            fields.add(new CourtDocumentRenderer.Field("Судья:", judgeName, 1));
            String decision = switch (verdict) {
                case "GUILTY" -> "Признать ответчика виновным.";
                case "ACQUITTED" -> "Ответчика оправдать.";
                default -> "Утвердить мировое соглашение сторон.";
            };
            fields.add(new CourtDocumentRenderer.Field("РЕШИЛ:", decision, 3));
            fields.add(new CourtDocumentRenderer.Field("Обоснование:", text, 9));
            if (!sanctionLines.isEmpty()) fields.add(new CourtDocumentRenderer.Field("Санкции:", String.join("\n", sanctionLines), 6));
            if (verdict.equals("GUILTY")) {
                fields.add(new CourtDocumentRenderer.Field("Обжалование:",
                        "до " + formatDate(appealUntilMillis) + " (" + settings.appealWindowHours() + " ч)", 1));
            }

            long now = System.currentTimeMillis();
            CourtDocumentRenderer.Document document = new CourtDocumentRenderer.Document(
                    settings.documentCourtName(), "РЕШЕНИЕ", pad(c.id()), russianDate(now), fields, judgeName,
                    "Документ сформирован автоматически · дело №" + pad(c.id()) + " · " + formatDate(now));
            return CourtDocumentRenderer.render(document, new CourtDocumentRenderer.Options(
                    settings.documentBackground(), settings.documentContentTop(), settings.documentMargin()));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Court: не удалось нарисовать документ решения (отправлю обычное объявление): " + t, t);
            return null;
        }
    }

    /** Ник судьи для документов: Minecraft-ник, если аккаунт привязан, иначе Discord-имя; fallback - переданное имя. */
    private String judgeDisplayName(String discordId, String fallback) {
        if (discordId == null || discordId.isBlank()) return fallback;
        List<UUID> uuids = uuidsOf(discordId);
        if (!uuids.isEmpty()) {
            String name = plugin.linkManager().nameFor(uuids.get(0));
            if (name != null && !name.isBlank()) return name;
        }
        try {
            JDA jda = jda();
            if (jda != null) return jda.retrieveUserById(discordId).complete().getName();
        } catch (Exception ignored) {
            // нет доступа к профилю - берём имя того, кто нажал кнопку
        }
        return fallback;
    }

    private String formatDate(long millis) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(settings.timezone()).format(Instant.ofEpochMilli(millis));
    }

    private String russianDate(long millis) {
        String[] months = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        var zoned = Instant.ofEpochMilli(millis).atZone(settings.timezone());
        return "«" + zoned.getDayOfMonth() + "» " + months[zoned.getMonthValue() - 1] + " " + zoned.getYear() + " г.";
    }

    public TerritoryResult<CourtCase> closeCase(int id, String judgeName) {
        try {
            if (!db().transitionCase(id, "CLOSED", "ACCEPTED", "SCHEDULED", "HEARING", "VERDICT")) {
                return TerritoryResult.fail("Дело уже закрыто или ещё не принято.");
            }
            db().markCaseClosed(id);
            CourtCase c = db().getCase(id);
            TextChannel room = c.channelId() == null ? null : textChannel(c.channelId());
            if (room != null) {
                room.sendMessageEmbeds(new EmbedBuilder().setColor(GRAY).setTitle("🔒 Дело закрыто")
                        .setDescription("Закрыл: " + judgeName).build()).queue(null, t -> { });
                archiveRoom(room, c);
            }
            audit("🔒 Дело №" + pad(id) + " закрыто (" + judgeName + ")");
            return TerritoryResult.ok(c);
        } catch (Exception e) {
            return internal(e);
        }
    }

    // ==================== Штрафы ====================

    public Fine issueFine(Integer caseId, PlayerRef target, long amount, String reason, String issuedBy, Integer days) throws Exception {
        long deadline = System.currentTimeMillis() + (days == null ? settings.fineDefaultDays() : days) * 86_400_000L;
        int id = db().insertFine(caseId, target.uuid().toString(), target.name(), target.discordId(), amount, cut(reason, 500), issuedBy, deadline);
        Fine fine = db().getFine(id);

        EmbedBuilder embed = fineEmbed(fine, "💸 Выставлен штраф №" + id).setColor(GOLD);
        announce(target.discordId() == null ? "" : "<@" + target.discordId() + ">", embed.build());
        dm(target.discordId(), embed.setFooter("Оплатить можно кнопкой ниже, пока идёт срок").build(), List.of(
                Button.success(P + "fine:pay:" + id, "💳 Оплатить"),
                Button.secondary(P + "fine:appeal:" + id, "📨 Обжаловать")));

        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            online.sendMessage("§6⚖ Тебе выставлен штраф №" + id + ": §f" + amount + " " + settings.currencyName()
                    + "§6. Оплата - кнопкой в Discord (бот суда), пока ты на сервере: " + (settings.payWithItems() ? "предметы спишутся из инвентаря." : "сумма спишется с баланса."));
        }
        return fine;
    }

    /** Оплата штрафа игроком: проверка баланса - списание командой сервера - проверка, что баланс реально упал. */
    public TerritoryResult<Fine> payFine(int id, String clickerDiscordId) {
        String claimedFrom = null;
        try {
            Fine fine = db().getFine(id);
            if (fine == null) return TerritoryResult.fail("Штраф не найден.");
            if (!isFineTarget(fine, clickerDiscordId)) return TerritoryResult.fail("Это не твой штраф.");
            if (fine.status().equals("PAID")) return TerritoryResult.fail("Этот штраф уже оплачен.");
            if (fine.status().equals("CANCELLED")) return TerritoryResult.fail("Этот штраф отменён.");

            for (String from : new String[]{"PENDING", "OVERDUE"}) {
                if (db().transitionFine(id, "PROCESSING", from)) {
                    claimedFrom = from;
                    break;
                }
            }
            if (claimedFrom == null) return TerritoryResult.fail("Штраф сейчас обрабатывается, подожди несколько секунд.");

            String chargeError = settings.payWithItems() ? chargeItems(fine) : chargeByCommand(fine);
            if (chargeError != null) {
                revert(id, claimedFrom);
                return TerritoryResult.fail(chargeError);
            }

            db().markFinePaid(id);
            Fine paid = db().getFine(id);
            MessageEmbed result = fineEmbed(paid, "✅ Штраф №" + id + " оплачен").setColor(GREEN).build();
            announce("", result);
            dm(paid.targetDiscord(), result, null);
            return TerritoryResult.ok(paid);
        } catch (Exception e) {
            if (claimedFrom != null) {
                try {
                    revert(id, claimedFrom);
                } catch (Exception ignored) {
                    // статус уже не вернуть - судья увидит PROCESSING в досье
                }
            }
            return internal(e);
        }
    }

    private void revert(int id, String status) throws Exception {
        db().transitionFine(id, status, "PROCESSING");
    }

    /** Режим ITEM: забирает предметы-валюту (ары = алмазная руда) из инвентаря игрока. Игрок должен быть на сервере. null = списано, иначе текст отказа. */
    private String chargeItems(Fine fine) throws Exception {
        java.util.Set<org.bukkit.Material> materials = new java.util.HashSet<>(settings.paymentItems());
        return onMain(() -> {
            Player player = Bukkit.getPlayer(UUID.fromString(fine.targetUuid()));
            if (player == null) {
                return "Штраф платится предметами из инвентаря - зайди на сервер и нажми «Оплатить» снова.";
            }
            org.bukkit.inventory.ItemStack[] contents = player.getInventory().getStorageContents();
            long have = 0;
            for (org.bukkit.inventory.ItemStack stack : contents) {
                if (stack != null && materials.contains(stack.getType())) have += stack.getAmount();
            }
            if (have < fine.amount()) {
                return "Недостаточно: нужно " + fine.amount() + " " + settings.currencyName() + ", в инвентаре " + have
                        + " (эндер-сундук не считается - достань нужное количество).";
            }
            long left = fine.amount();
            for (int i = 0; i < contents.length && left > 0; i++) {
                org.bukkit.inventory.ItemStack stack = contents[i];
                if (stack == null || !materials.contains(stack.getType())) continue;
                int take = (int) Math.min(left, stack.getAmount());
                if (take == stack.getAmount()) contents[i] = null;
                else stack.setAmount(stack.getAmount() - take);
                left -= take;
            }
            player.getInventory().setStorageContents(contents);
            return null;
        });
    }

    /** Режим COMMAND: проверка баланса по плейсхолдеру - списание командой консоли - проверка, что баланс реально упал. */
    private String chargeByCommand(Fine fine) throws Exception {
        UUID uuid = UUID.fromString(fine.targetUuid());
        String placeholder = settings.balancePlaceholder();
        Double before = null;
        if (!placeholder.isBlank()) {
            before = readBalance(uuid, placeholder);
            if (before == null) return "Не удалось прочитать твой баланс. Зайди на сервер и повтори оплату.";
            if (before < fine.amount()) {
                return "Недостаточно средств: нужно " + fine.amount() + " " + settings.currencyName() + ", у тебя " + trimNumber(before) + ".";
            }
        }

        String command = settings.chargeCommand().replace("{player}", fine.targetName()).replace("{amount}", String.valueOf(fine.amount()));
        onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));

        if (before != null) {
            boolean charged = false;
            for (int attempt = 0; attempt < 3 && !charged; attempt++) {
                Thread.sleep(1500);
                Double after = readBalance(uuid, placeholder);
                charged = after != null && before - after >= fine.amount() - 0.0001;
            }
            if (!charged) {
                audit("⚠ Штраф №" + fine.id() + ": списание " + fine.amount() + " с " + fine.targetName()
                        + " не подтвердилось (баланс не изменился). Проверь команду court.fines.charge-command.");
                return "Списание не подтвердилось. Обратись к судье, штраф остался неоплаченным.";
            }
        }
        return null;
    }

    private Double readBalance(UUID uuid, String placeholder) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            String text = onMain(() -> PlaceholderAPI.setPlaceholders(player, placeholder));
            if (text == null || text.equals(placeholder)) return null;
            String cleaned = text.replaceAll("§.", "").replaceAll("[^0-9.,\\-]", "").replace(',', '.');
            int lastDot = cleaned.lastIndexOf('.');
            if (lastDot >= 0) cleaned = cleaned.substring(0, lastDot).replace(".", "") + cleaned.substring(lastDot);
            if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) return null;
            return new BigDecimal(cleaned).doubleValue();
        } catch (Exception e) {
            return null;
        }
    }

    private void refund(Fine fine) throws Exception {
        if (settings.payWithItems()) {
            refundItems(fine);
            return;
        }
        String command = settings.refundCommand().replace("{player}", fine.targetName()).replace("{amount}", String.valueOf(fine.amount()));
        if (command.isBlank()) return;
        onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    /** Возврат предметами при отмене оплаченного штрафа. Если игрока нет на сервере - предметы не выдать, об этом пишется в лог суда (вернуть вручную). */
    private void refundItems(Fine fine) throws Exception {
        org.bukkit.Material material = settings.paymentItems().get(0);
        Boolean given = onMain(() -> {
            Player player = Bukkit.getPlayer(UUID.fromString(fine.targetUuid()));
            if (player == null) return false;
            long left = fine.amount();
            while (left > 0) {
                int chunk = (int) Math.min(left, material.getMaxStackSize());
                var overflow = player.getInventory().addItem(new org.bukkit.inventory.ItemStack(material, chunk));
                for (var rest : overflow.values()) player.getWorld().dropItem(player.getLocation(), rest);
                left -= chunk;
            }
            player.sendMessage("§a⚖ Штраф №" + fine.id() + " отменён, тебе возвращено: §f" + fine.amount() + " " + settings.currencyName() + "§a.");
            return true;
        });
        if (!given) {
            audit("⚠ Штраф №" + fine.id() + " отменён, но игрока " + fine.targetName() + " нет на сервере - верни ему вручную "
                    + fine.amount() + " " + settings.currencyName() + " (" + material.name() + ").");
        }
    }

    // ==================== Постановления ====================

    public Decree issueDecree(Integer caseId, PlayerRef target, String type, String duration, String reason, String issuedBy) throws Exception {
        int id = db().insertDecree(caseId, target.uuid().toString(), target.name(), type, duration, cut(reason, 500), issuedBy);
        Decree decree = db().getDecree(id);
        moderation.execute(ModerationExecutor.Action.valueOf(type), target.name(), duration, reason, issuedBy + " (суд)");

        EmbedBuilder embed = new EmbedBuilder().setColor(RED).setTitle("🔨 Постановление №" + id)
                .addField("Нарушитель", mention(target.discordId(), target.name()), true)
                .addField("Мера", decreeLabel(decree), true)
                .addField("Причина", cut(reason, 1000), false)
                .addField("Вынес", issuedBy, true);
        if (caseId != null) embed.addField("Дело", "№" + pad(caseId), true);
        announce(target.discordId() == null ? "" : "<@" + target.discordId() + ">", embed.build());
        dm(target.discordId(), embed.build(), null);
        return decree;
    }

    private void revokeDecree(Decree d) throws Exception {
        String template = switch (d.type()) {
            case "BAN" -> settings.revokeBanCommand();
            case "MUTE" -> settings.revokeMuteCommand();
            default -> "";
        };
        if (!template.isBlank()) {
            String command = template.replace("{player}", d.targetName());
            onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
        db().markDecreeRevoked(d.id());
    }

    // ==================== Апелляции ====================

    public TerritoryResult<Void> fileAppeal(String kind, int id, String discordId, String reason) {
        try {
            long now = System.currentTimeMillis();
            long window = settings.appealWindowHours() * 3_600_000L;
            EmbedBuilder embed = new EmbedBuilder().setColor(BLUE).addField("Основание", cut(reason, 1000), false);
            if (kind.equals("case")) {
                CourtCase c = db().getCase(id);
                if (c == null) return TerritoryResult.fail("Дело не найдено.");
                if (!isDefendant(c, discordId)) return TerritoryResult.fail("Обжаловать может только ответчик.");
                if (c.verdictAt() == null || !"GUILTY".equals(c.verdict())) return TerritoryResult.fail("Обжаловать нечего: обвинительного вердикта нет.");
                if (c.appeal() != null) return TerritoryResult.fail("Апелляция по этому делу уже подана.");
                if (now - c.verdictAt() > window) return TerritoryResult.fail("Срок обжалования (" + settings.appealWindowHours() + " ч) истёк.");
                db().setCaseAppeal(id, "PENDING");
                embed.setTitle("📨 Апелляция на вердикт по делу №" + pad(id)).setDescription(cut(c.title(), 300))
                        .addField("Подал", mention(c.defendantDiscord(), c.defendantName()), true)
                        .addField("Вердикт", cut(c.verdictText() == null ? "-" : c.verdictText(), 500), false);
            } else {
                Fine f = db().getFine(id);
                if (f == null) return TerritoryResult.fail("Штраф не найден.");
                if (!isFineTarget(f, discordId)) return TerritoryResult.fail("Это не твой штраф.");
                if (f.status().equals("CANCELLED")) return TerritoryResult.fail("Штраф уже отменён.");
                if (f.appeal() != null) return TerritoryResult.fail("Апелляция по этому штрафу уже подана.");
                if (now - f.createdAt() > window) return TerritoryResult.fail("Срок обжалования (" + settings.appealWindowHours() + " ч) истёк.");
                db().setFineAppeal(id, "PENDING");
                embed.setTitle("📨 Апелляция на штраф №" + id)
                        .addField("Штраф", f.amount() + " " + settings.currencyName() + " — " + cut(f.reason(), 300), false)
                        .addField("Подал", mention(f.targetDiscord(), f.targetName()), true);
            }
            TextChannel channel = textChannel(settings.appealsChannelId());
            if (channel == null) {
                plugin.getLogger().warning("Court: канал апелляций не найден - апелляция не показана главным судьям.");
            } else {
                var action = channel.sendMessageEmbeds(embed.build())
                        .addActionRow(Button.success(P + "appeal:uphold:" + kind + ":" + id, "✅ Оставить в силе"),
                                Button.danger(P + "appeal:overturn:" + kind + ":" + id, "↩ Отменить решение"));
                String ping = roleMentions(true);
                if (!ping.isBlank()) action = action.setContent(ping);
                action.queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить апелляцию: " + t.getMessage()));
            }
            return TerritoryResult.ok(null);
        } catch (Exception e) {
            return internal(e);
        }
    }

    public TerritoryResult<String> decideAppeal(String kind, int id, boolean overturn, String judgeName) {
        try {
            String outcome = overturn ? "решение отменено" : "решение оставлено в силе";
            if (kind.equals("case")) {
                CourtCase c = db().getCase(id);
                if (c == null) return TerritoryResult.fail("Дело не найдено.");
                if (!"PENDING".equals(c.appeal())) return TerritoryResult.fail("По этому делу нет нерассмотренной апелляции.");
                db().setCaseAppeal(id, overturn ? "GRANTED" : "DENIED");
                if (overturn) {
                    for (Decree d : db().decreesForCase(id)) {
                        if (d.status().equals("ACTIVE")) revokeDecree(d);
                    }
                    for (Fine f : db().finesForCase(id)) cancelFine(f);
                    db().setCaseVerdictOnly(id, "OVERTURNED");
                }
                MessageEmbed embed = new EmbedBuilder().setColor(overturn ? GREEN : GRAY)
                        .setTitle("📨 Апелляция по делу №" + pad(id) + ": " + outcome)
                        .addField("Рассмотрел", judgeName, true).build();
                announce(partyMentions(c), embed);
                dmBoth(c, embed);
            } else {
                Fine f = db().getFine(id);
                if (f == null) return TerritoryResult.fail("Штраф не найден.");
                if (!"PENDING".equals(f.appeal())) return TerritoryResult.fail("По этому штрафу нет нерассмотренной апелляции.");
                db().setFineAppeal(id, overturn ? "GRANTED" : "DENIED");
                if (overturn) cancelFine(f);
                MessageEmbed embed = new EmbedBuilder().setColor(overturn ? GREEN : GRAY)
                        .setTitle("📨 Апелляция по штрафу №" + id + ": " + outcome)
                        .addField("Рассмотрел", judgeName, true).build();
                announce(f.targetDiscord() == null ? "" : "<@" + f.targetDiscord() + ">", embed);
                dm(f.targetDiscord(), embed, null);
            }
            return TerritoryResult.ok(outcome);
        } catch (Exception e) {
            return internal(e);
        }
    }

    /** Отмена штрафа: неоплаченный просто гасится, оплаченный возвращается игроку командой возврата. */
    private void cancelFine(Fine f) throws Exception {
        if (f.status().equals("PAID")) {
            if (db().transitionFine(f.id(), "CANCELLED", "PAID")) refund(f);
        } else {
            db().transitionFine(f.id(), "CANCELLED", "PENDING", "OVERDUE");
        }
    }

    // ==================== Планировщик ====================

    private void tick() throws Exception {
        if (db == null || !bot.isReady()) return;
        long now = System.currentTimeMillis();

        for (CourtCase c : db().casesDueForHearing(now)) startHearing(c.id());

        long window = settings.fineReminderHours() * 3_600_000L;
        if (window > 0) {
            for (Fine f : db().finesNeedingReminder(now, window)) {
                db().markFineReminded(f.id());
                dm(f.targetDiscord(), fineEmbed(f, "⏰ Срок оплаты штрафа №" + f.id() + " истекает").setColor(GOLD).build(),
                        List.of(Button.success(P + "fine:pay:" + f.id(), "💳 Оплатить")));
            }
        }

        for (Fine f : db().overdueFines(now)) {
            if (!db().transitionFine(f.id(), "OVERDUE", "PENDING")) continue;
            MessageEmbed embed = fineEmbed(f, "⌛ Штраф №" + f.id() + " просрочен").setColor(RED).build();
            announce(f.targetDiscord() == null ? "" : "<@" + f.targetDiscord() + ">", embed);
            dm(f.targetDiscord(), embed, List.of(Button.success(P + "fine:pay:" + f.id(), "💳 Оплатить")));
            String command = settings.overdueCommand().replace("{player}", f.targetName())
                    .replace("{amount}", String.valueOf(f.amount())).replace("{id}", String.valueOf(f.id())).trim();
            if (!command.isBlank()) onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        async(() -> refreshRoleTag(player.getUniqueId()));
        if (db == null) return;
        async(() -> {
            int unpaid = db().countUnpaidFines(player.getUniqueId().toString());
            if (unpaid > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage("§c⚖ У тебя неоплаченных штрафов: §f" + unpaid + "§c. Оплатить можно в Discord через бота суда.");
                    }
                }, 100L);
            }
            // судье, который не был в игре в момент вердикта, выдаём книги-архивы дел при входе
            String discordId = plugin.linkManager().discordIdFor(player.getUniqueId());
            if (discordId != null) {
                for (CourtCase c : db().casesAwaitingArchive(discordId)) {
                    if (player.isOnline()) giveArchive(player, c);
                }
            }
        });
    }

    // ==================== Списки и досье ====================

    public EmbedBuilder myCasesEmbed(String discordId) throws Exception {
        List<CourtCase> cases = db().casesForDiscords(List.of(discordId), 10);
        EmbedBuilder embed = new EmbedBuilder().setColor(BLUE).setTitle("📂 Мои дела");
        if (cases.isEmpty()) return embed.setDescription("У тебя нет дел.");
        StringBuilder sb = new StringBuilder();
        for (CourtCase c : cases) {
            sb.append("**№").append(pad(c.id())).append("** — ").append(cut(c.title(), 60)).append(" · ")
                    .append(statusLabel(c.status())).append(c.verdict() == null ? "" : " · " + verdictLabel(c.verdict())).append('\n');
        }
        return embed.setDescription(cut(sb.toString(), 3900));
    }

    public record FinesView(EmbedBuilder embed, List<Button> payButtons) {
    }

    public FinesView myFines(String discordId) throws Exception {
        List<String> uuids = new ArrayList<>();
        for (UUID u : uuidsOf(discordId)) uuids.add(u.toString());
        List<Fine> fines = db().finesForTargets(uuids, 10);
        EmbedBuilder embed = new EmbedBuilder().setColor(GOLD).setTitle("💸 Мои штрафы");
        List<Button> buttons = new ArrayList<>();
        if (fines.isEmpty()) return new FinesView(embed.setDescription("Штрафов нет."), buttons);
        StringBuilder sb = new StringBuilder();
        for (Fine f : fines) {
            sb.append("**№").append(f.id()).append("** — ").append(f.amount()).append(' ').append(settings.currencyName())
                    .append(" · ").append(fineStatusLabel(f.status())).append(" · до <t:").append(f.deadline() / 1000).append(":d>\n")
                    .append("  ").append(cut(f.reason(), 80)).append('\n');
            if ((f.status().equals("PENDING") || f.status().equals("OVERDUE")) && buttons.size() < 5) {
                buttons.add(Button.success(P + "fine:pay:" + f.id(), "💳 №" + f.id()));
            }
        }
        return new FinesView(embed.setDescription(cut(sb.toString(), 3900)), buttons);
    }

    public EmbedBuilder dossierEmbed(PlayerRef target) throws Exception {
        String uuid = target.uuid().toString();
        List<CourtCase> cases = db().casesAgainst(uuid, 10);
        List<Fine> fines = db().finesForTargets(List.of(uuid), 50);
        List<Decree> decrees = db().decreesForTarget(uuid, 10);

        long guilty = cases.stream().filter(c -> "GUILTY".equals(c.verdict())).count();
        long fined = fines.stream().filter(f -> !f.status().equals("CANCELLED")).mapToLong(Fine::amount).sum();
        long paid = fines.stream().filter(f -> f.status().equals("PAID")).mapToLong(Fine::amount).sum();
        long unpaid = fines.stream().filter(f -> f.status().equals("PENDING") || f.status().equals("OVERDUE")).mapToLong(Fine::amount).sum();
        long active = decrees.stream().filter(d -> d.status().equals("ACTIVE")).count();

        EmbedBuilder embed = new EmbedBuilder().setColor(BLUE).setTitle("🗂 Досье: " + target.name())
                .addField("Дела против него", cases.size() + " (виновен: " + guilty + ")", true)
                .addField("Штрафы", "выставлено " + fined + ", оплачено " + paid + ", долг " + unpaid, true)
                .addField("Постановления", decrees.size() + " (действует: " + active + ")", true);
        if (!cases.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (CourtCase c : cases.subList(0, Math.min(5, cases.size()))) {
                sb.append("№").append(pad(c.id())).append(" — ").append(cut(c.title(), 50)).append(" · ")
                        .append(c.verdict() == null ? statusLabel(c.status()) : verdictLabel(c.verdict())).append('\n');
            }
            embed.addField("Последние дела", cut(sb.toString(), 1000), false);
        }
        if (!decrees.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Decree d : decrees.subList(0, Math.min(5, decrees.size()))) {
                sb.append("№").append(d.id()).append(" — ").append(decreeLabel(d)).append(" · ")
                        .append(d.status().equals("ACTIVE") ? "действует" : "отменено").append('\n');
            }
            embed.addField("Последние постановления", cut(sb.toString(), 1000), false);
        }
        return embed;
    }

    public CourtCase getCase(int id) throws Exception {
        return db().getCase(id);
    }

    // ==================== Discord-помощники ====================

    public EmbedBuilder caseEmbed(CourtCase c) {
        EmbedBuilder e = new EmbedBuilder().setColor(statusColor(c.status()))
                .setTitle("⚖ Дело №" + pad(c.id()) + " — " + cut(c.title(), 200))
                .addField("Истец", mention(c.plaintiffDiscord(), c.plaintiffName()), true)
                .addField("Ответчик", mention(c.defendantDiscord(), c.defendantName()), true)
                .addField("Статус", statusLabel(c.status()), true)
                .addField("Суть иска", cut(c.claim(), 1000), false);
        if (c.evidence() != null) e.addField("Доказательства", cut(c.evidence(), 1000), false);
        if (c.judgeDiscord() != null) e.addField("Судья", "<@" + c.judgeDiscord() + ">", true);
        if (c.hearingAt() != null) e.addField("Заседание", "<t:" + c.hearingAt() / 1000 + ":f> (<t:" + c.hearingAt() / 1000 + ":R>)", true);
        if (c.verdict() != null) {
            e.addField("Вердикт", verdictLabel(c.verdict()) + (c.verdictText() == null ? "" : "\n" + cut(c.verdictText(), 900)), false);
        }
        if (c.appeal() != null) e.addField("Апелляция", appealLabel(c.appeal()), true);
        return e;
    }

    public EmbedBuilder fineEmbed(Fine f, String title) {
        EmbedBuilder e = new EmbedBuilder().setTitle(title)
                .addField("Нарушитель", mention(f.targetDiscord(), f.targetName()), true)
                .addField("Сумма", f.amount() + " " + settings.currencyName(), true)
                .addField("Статус", fineStatusLabel(f.status()), true)
                .addField("Причина", cut(f.reason(), 1000), false)
                .addField("Срок оплаты", "<t:" + f.deadline() / 1000 + ":f> (<t:" + f.deadline() / 1000 + ":R>)", false)
                .addField("Выставил", f.issuedBy(), true);
        if (f.caseId() != null) e.addField("Дело", "№" + pad(f.caseId()), true);
        if (f.appeal() != null) e.addField("Апелляция", appealLabel(f.appeal()), true);
        return e;
    }

    public List<Button> judgeRow1(int id) {
        return List.of(Button.primary(P + "case:hearing:" + id, "📅 Назначить заседание"),
                Button.secondary(P + "case:start:" + id, "▶ Начать заседание"),
                Button.danger(P + "case:close:" + id, "🔒 Закрыть дело"));
    }

    public List<Button> judgeRow2(int id) {
        return List.of(Button.danger(P + "case:verdict:guilty:" + id, "⚖ Виновен"),
                Button.success(P + "case:verdict:acquit:" + id, "✅ Оправдан"),
                Button.secondary(P + "case:verdict:settle:" + id, "🤝 Мировое"));
    }

    private TextChannel textChannel(String id) {
        JDA jda = jda();
        if (jda == null || id == null || id.isBlank()) return null;
        return jda.getTextChannelById(id);
    }

    private void announce(String content, MessageEmbed embed) {
        TextChannel channel = textChannel(settings.announcementsChannelId());
        if (channel == null) {
            plugin.getLogger().warning("Court: канал court.channels.announcements не найден - объявление не отправлено.");
            return;
        }
        var action = channel.sendMessageEmbeds(embed);
        if (content != null && !content.isBlank()) action = action.setContent(content);
        action.queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить объявление: " + t.getMessage()));
    }

    private void audit(String text) {
        TextChannel channel = textChannel(settings.logChannelId());
        if (channel == null) return;
        channel.sendMessageEmbeds(new EmbedBuilder().setColor(GRAY).setDescription(cut(text, 3900)).setTimestamp(Instant.now()).build())
                .queue(null, t -> { });
    }

    private void room(CourtCase c, String content, MessageEmbed embed, Collection<Button> buttons) {
        TextChannel room = c.channelId() == null ? null : textChannel(c.channelId());
        if (room == null) return;
        var action = room.sendMessageEmbeds(embed);
        if (content != null && !content.isBlank()) action = action.setContent(content);
        if (buttons != null && !buttons.isEmpty()) action = action.addActionRow(buttons);
        action.queue(null, t -> { });
    }

    private void dm(String discordId, MessageEmbed embed, Collection<Button> buttons) {
        JDA jda = jda();
        if (jda == null || discordId == null || discordId.isBlank()) return;
        jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(channel -> {
            var action = channel.sendMessageEmbeds(embed);
            if (buttons != null && !buttons.isEmpty()) action = action.addActionRow(buttons);
            action.queue(null, t -> { });
        }, t -> { }), t -> { });
    }

    private void announceImage(String content, byte[] png, String fileName) {
        TextChannel channel = textChannel(settings.announcementsChannelId());
        if (channel == null) {
            plugin.getLogger().warning("Court: канал court.channels.announcements не найден - объявление не отправлено.");
            return;
        }
        channel.sendMessage(content).addFiles(FileUpload.fromData(png, fileName))
                .queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить документ в объявления: " + t.getMessage()));
    }

    private void roomImage(CourtCase c, byte[] png, String fileName) {
        TextChannel room = c.channelId() == null ? null : textChannel(c.channelId());
        if (room == null) return;
        room.sendMessage("📄 Решение по делу №" + pad(c.id())).addFiles(FileUpload.fromData(png, fileName)).queue(null, t -> { });
    }

    private void dmImage(String discordId, String content, byte[] png, String fileName, Collection<Button> buttons) {
        JDA jda = jda();
        if (jda == null || discordId == null || discordId.isBlank()) return;
        jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(channel -> {
            var action = channel.sendMessage(content).addFiles(FileUpload.fromData(png, fileName));
            if (buttons != null && !buttons.isEmpty()) action = action.addActionRow(buttons);
            action.queue(null, t -> { });
        }, t -> { }), t -> { });
    }

    private void dmBoth(CourtCase c, MessageEmbed embed) {
        dm(c.plaintiffDiscord(), embed, null);
        dm(c.defendantDiscord(), embed, null);
    }

    private String roleMentions() {
        return roleMentions(false);
    }

    private String roleMentions(boolean chiefOnly) {
        List<String> ids = new ArrayList<>(settings.chiefRoleIds());
        if (!chiefOnly) ids.addAll(settings.judgeRoleIds());
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (!id.isBlank() && sb.indexOf("<@&" + id + ">") < 0) sb.append("<@&").append(id).append("> ");
        }
        return sb.toString().trim();
    }

    private String partyMentions(CourtCase c) {
        StringBuilder sb = new StringBuilder("<@" + c.plaintiffDiscord() + ">");
        if (c.defendantDiscord() != null) sb.append(" <@").append(c.defendantDiscord()).append('>');
        if (c.judgeDiscord() != null) sb.append(" <@").append(c.judgeDiscord()).append('>');
        return sb.toString();
    }

    private String mention(String discordId, String name) {
        return discordId == null || discordId.isBlank() ? name : "<@" + discordId + "> (" + name + ")";
    }

    // ==================== Комната дела ====================

    private TextChannel createCaseRoom(Guild guild, CourtCase c, String judgeDiscordId) {
        try {
            Category category = resolveCategory(guild);
            EnumSet<Permission> allow = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                    Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);
            var action = guild.createTextChannel("delo-" + pad(c.id()))
                    .setParent(category)
                    .setTopic("court-case:" + c.id())
                    .addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.VIEW_CHANNEL))
                    .addPermissionOverride(guild.getSelfMember(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                            Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_CHANNEL), EnumSet.noneOf(Permission.class));

            List<String> roleIds = new ArrayList<>(settings.judgeRoleIds());
            roleIds.addAll(settings.chiefRoleIds());
            for (String roleId : roleIds) {
                Role role = roleId.isBlank() ? null : guild.getRoleById(roleId);
                if (role != null) action = action.addPermissionOverride(role, allow, EnumSet.noneOf(Permission.class));
            }
            List<String> people = new ArrayList<>(List.of(c.plaintiffDiscord(), judgeDiscordId));
            if (c.defendantDiscord() != null) people.add(c.defendantDiscord());
            for (String id : new java.util.LinkedHashSet<>(people)) {
                try {
                    Member member = guild.retrieveMemberById(id).complete();
                    action = action.addPermissionOverride(member, allow, EnumSet.noneOf(Permission.class));
                } catch (Exception ignored) {
                    // участник не на сервере - в комнате его не будет, но объявления и ЛС он всё равно получит
                }
            }
            return action.complete();
        } catch (Exception e) {
            plugin.getLogger().warning("Court: не удалось создать комнату дела №" + c.id() + ": " + e.getMessage());
            return null;
        }
    }

    private Category resolveCategory(Guild guild) {
        String id = settings.caseCategoryId();
        if (!id.isBlank()) {
            Category byId = guild.getCategoryById(id.trim());
            if (byId != null) return byId;
        }
        List<Category> byName = guild.getCategoriesByName("Суд", true);
        if (!byName.isEmpty()) return byName.get(0);
        return guild.createCategory("Суд").complete();
    }

    private void archiveRoom(TextChannel room, CourtCase c) {
        for (String id : new String[]{c.plaintiffDiscord(), c.defendantDiscord()}) {
            if (id == null) continue;
            room.getGuild().retrieveMemberById(id).queue(
                    member -> room.upsertPermissionOverride(member).deny(Permission.MESSAGE_SEND).queue(null, t -> { }),
                    t -> { });
        }
        String name = "closed-" + room.getName();
        room.getManager().setName(name.length() > 100 ? name.substring(0, 100) : name).queue(null, t -> { });

        if (settings.transcripts()) {
            room.getIterableHistory().takeAsync(3000).whenComplete((messages, error) -> {
                if (error != null) {
                    plugin.getLogger().warning("Court: не удалось прочитать историю комнаты для протокола: " + error.getMessage());
                    return;
                }
                TextChannel log = textChannel(settings.logChannelId());
                if (log == null) return;
                List<Message> chronological = new ArrayList<>(messages);
                Collections.reverse(chronological);
                StringBuilder sb = new StringBuilder("Протокол дела №" + pad(c.id()) + " — " + c.title() + "\n" + "=".repeat(60) + "\n\n");
                for (Message m : chronological) {
                    User author = m.getAuthor();
                    sb.append('[').append(TRANSCRIPT_TIME.format(m.getTimeCreated())).append("] ").append(author.getName())
                            .append(" (").append(author.getId()).append("): ").append(m.getContentDisplay().replace("\n", "\n    ")).append('\n');
                    for (Message.Attachment a : m.getAttachments()) sb.append("    [вложение] ").append(a.getFileName()).append(" - ").append(a.getUrl()).append('\n');
                    for (MessageEmbed embed : m.getEmbeds()) {
                        if (embed.getTitle() != null) sb.append("    [embed] ").append(embed.getTitle()).append('\n');
                        if (embed.getDescription() != null) sb.append("    ").append(embed.getDescription().replace("\n", "\n    ")).append('\n');
                    }
                }
                log.sendMessage("📜 Протокол дела №" + pad(c.id()))
                        .addFiles(FileUpload.fromData(sb.toString().getBytes(StandardCharsets.UTF_8), "delo-" + pad(c.id()) + ".txt"))
                        .queue(null, t -> plugin.getLogger().warning("Court: не удалось отправить протокол: " + t.getMessage()));
            });
        }
    }

    // ==================== Подписи ====================

    public static String pad(int number) {
        return String.format("%04d", number);
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String trimNumber(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case "FILED" -> "📥 Подан";
            case "ACCEPTED" -> "✅ Принят";
            case "SCHEDULED" -> "📅 Назначено заседание";
            case "HEARING" -> "⚖ Идёт заседание";
            case "VERDICT" -> "🔨 Вынесен вердикт";
            case "CLOSED" -> "🔒 Закрыто";
            case "REJECTED" -> "❌ Отклонён";
            default -> status;
        };
    }

    private static Color statusColor(String status) {
        return switch (status) {
            case "REJECTED" -> RED;
            case "VERDICT" -> GOLD;
            case "CLOSED" -> GRAY;
            default -> BLUE;
        };
    }

    public static String verdictLabel(String verdict) {
        return switch (verdict) {
            case "GUILTY" -> "Виновен";
            case "ACQUITTED" -> "Оправдан";
            case "SETTLED" -> "Мировое соглашение";
            case "OVERTURNED" -> "Отменён апелляцией";
            default -> verdict;
        };
    }

    private static String fineStatusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "⏳ Ожидает оплаты";
            case "PROCESSING" -> "⏳ Обрабатывается";
            case "PAID" -> "✅ Оплачен";
            case "OVERDUE" -> "⌛ Просрочен";
            case "CANCELLED" -> "↩ Отменён";
            default -> status;
        };
    }

    private static String appealLabel(String appeal) {
        return switch (appeal) {
            case "PENDING" -> "На рассмотрении";
            case "GRANTED" -> "Удовлетворена";
            case "DENIED" -> "Отклонена";
            default -> appeal;
        };
    }

    private static String decreeLabel(Decree d) {
        String type = switch (d.type()) {
            case "BAN" -> "Бан";
            case "MUTE" -> "Мут";
            case "KICK" -> "Кик";
            default -> "Предупреждение";
        };
        return d.duration() == null ? type : type + " на " + d.duration();
    }
}
