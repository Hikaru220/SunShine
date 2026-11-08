package com.sunshine.plugin.court;

import com.sunshine.plugin.SunShinePlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.List;

/** Настройки суда из секции {@code court:} config.yml - читаются на лету из актуального конфига, перезагрузка (/sunshine reload) подхватывается без рестарта. */
public final class CourtSettings {

    private final SunShinePlugin plugin;

    public CourtSettings(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    public boolean enabled() {
        return c().getBoolean("court.enabled", false);
    }

    public String token() {
        return c().getString("court.token", "");
    }

    public List<String> judgeRoleIds() {
        return c().getStringList("court.judge-role-ids");
    }

    public List<String> chiefRoleIds() {
        return c().getStringList("court.chief-judge-role-ids");
    }

    public String inboxChannelId() {
        return c().getString("court.channels.inbox", "");
    }

    public String announcementsChannelId() {
        return c().getString("court.channels.announcements", "");
    }

    /** Апелляции уходят в отдельный канал, если он не задан - в тот же inbox. */
    public String appealsChannelId() {
        String id = c().getString("court.channels.appeals", "");
        return id.isBlank() ? inboxChannelId() : id;
    }

    public String logChannelId() {
        return c().getString("court.channels.log", "");
    }

    public String caseCategoryId() {
        return c().getString("court.channels.case-category", "");
    }

    public ZoneId timezone() {
        try {
            return ZoneId.of(c().getString("court.timezone", "Europe/Moscow"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    public int maxOpenCasesPerPlayer() {
        return Math.max(1, c().getInt("court.max-open-cases-per-player", 3));
    }

    /** Картинка-документ с итогами дела (решение суда). Выключено = в объявлениях обычный embed. */
    public boolean documentEnabled() {
        return c().getBoolean("court.document.enabled", true);
    }

    public String documentCourtName() {
        return c().getString("court.document.court-name", "СУД SUNSHINE");
    }

    /** Свой бланк-фон (PNG/JPG, лучше 1240x1754) относительно папки плагина. null = рисовать стандартный бланк с рамкой и печатью. */
    public java.io.File documentBackground() {
        String path = c().getString("court.document.background", "");
        return path.isBlank() ? null : new java.io.File(plugin.getDataFolder(), path);
    }

    /** Отступ сверху до начала текста - нужен только для своего бланка. */
    public int documentContentTop() {
        return c().getInt("court.document.content-top", 260);
    }

    public int documentMargin() {
        return c().getInt("court.document.margin", 110);
    }

    /** Знак судьи в чате/табе через плейсхолдеры %sunshinecourt_*%. */
    public boolean tagsEnabled() {
        return c().getBoolean("court.tags.enabled", true);
    }

    public String tagJudge() {
        return c().getString("court.tags.judge", "&6⚖");
    }

    public String tagChief() {
        return c().getString("court.tags.chief", "&c⚖");
    }

    /** Плейсхолдер префикса игрока: если он не пустой, судье весы идут суффиксом, если пустой - префиксом. */
    public String tagPrefixPlaceholder() {
        return c().getString("court.tags.prefix-placeholder", "%luckperms_prefix%");
    }

    public int appealWindowHours() {
        return Math.max(1, c().getInt("court.appeal-window-hours", 48));
    }

    public boolean transcripts() {
        return c().getBoolean("court.transcripts", false);
    }

    public int fineDefaultDays() {
        return Math.max(1, c().getInt("court.fines.default-days", 3));
    }

    public long fineMaxAmount() {
        return Math.max(1, c().getLong("court.fines.max-amount", 2304L));
    }

    public int fineReminderHours() {
        return Math.max(0, c().getInt("court.fines.reminder-hours", 24));
    }

    public String currencyName() {
        return c().getString("court.fines.currency-name", "ар");
    }

    /** ITEM (по умолчанию) - штраф платится предметами из инвентаря игрока (ары = алмазная руда); COMMAND - командой консоли и проверкой баланса. */
    public boolean payWithItems() {
        return !"COMMAND".equalsIgnoreCase(c().getString("court.fines.payment", "ITEM"));
    }

    /** Предметы, которые считаются валютой штрафа (в режиме ITEM). Первый в списке выдаётся при возврате. */
    public List<org.bukkit.Material> paymentItems() {
        List<org.bukkit.Material> result = new java.util.ArrayList<>();
        for (String name : c().getStringList("court.fines.items")) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(name.trim());
            if (material != null) result.add(material);
        }
        if (result.isEmpty()) {
            result.add(org.bukkit.Material.DIAMOND_ORE);
            result.add(org.bukkit.Material.DEEPSLATE_DIAMOND_ORE);
        }
        return result;
    }

    /** Команда списания с игрока. Плейсхолдеры: {player}, {amount}. */
    public String chargeCommand() {
        return c().getString("court.fines.charge-command", "sunadmin remove {player} {amount}");
    }

    /** Команда возврата при отмене оплаченного штрафа. Плейсхолдеры: {player}, {amount}. */
    public String refundCommand() {
        return c().getString("court.fines.refund-command", "sunadmin add {player} {amount}");
    }

    /** PlaceholderAPI-плейсхолдер баланса - по нему проверяем, что денег хватает и что списание реально прошло. Пусто = не проверять. */
    public String balancePlaceholder() {
        return c().getString("court.fines.balance-placeholder", "%suncoins_balance%");
    }

    /** Команда при просрочке штрафа (пусто = ничего). Плейсхолдеры: {player}, {amount}, {id}. */
    public String overdueCommand() {
        return c().getString("court.fines.overdue-command", "");
    }

    public String revokeBanCommand() {
        return c().getString("court.revoke.ban", "unban {player}");
    }

    public String revokeMuteCommand() {
        return c().getString("court.revoke.mute", "unmute {player}");
    }
}
