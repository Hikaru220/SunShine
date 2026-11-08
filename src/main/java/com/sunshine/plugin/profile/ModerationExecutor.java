package com.sunshine.plugin.profile;

import com.sunshine.plugin.SunShinePlugin;
import org.bukkit.Bukkit;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выполняет команды модерации FlectonePulse (бан/мут/кик/варн) от лица консоли - общий код для
 * кнопок под Discord-эмбедом /profile и для игровой команды {@code /sunshine punish}. Точные
 * шаблоны команд берутся из конфига ({@code profile.commands.*}), т.к. лейблы команд у
 * FlectonePulse настраиваются и могут отличаться от сервера к серверу.
 * <p>
 * Само логирование в Discord (channels.moderation-log) делает {@link ModerationLogPoller} - он
 * следит за таблицей fp_moderation напрямую, поэтому ловит вообще все наказания, а не только
 * выданные через SunShine (в т.ч. набранные прямо в консоли сервера или через игровые команды
 * в обход /profile). Раз команда выполняется от лица консоли, FlectonePulse сам запишет
 * модератором "Console" - чтобы в логе всё равно был виден настоящий инициатор (админ в Discord
 * или игрок, нажавший кнопку), этот класс на короткое время (см. {@link #ATTRIBUTION_TTL_MS})
 * запоминает, кто именно вызвал команду через SunShine, а {@link ModerationLogPoller} подставляет
 * это имя вместо "Console" при появлении новой записи для того же игрока.
 */
public final class ModerationExecutor {

    private static final long ATTRIBUTION_TTL_MS = 30_000L;
    private static final Map<String, Attribution> RECENT_ATTRIBUTIONS = new ConcurrentHashMap<>();

    private record Attribution(String moderatorName, long atMillis) {
    }

    private final SunShinePlugin plugin;

    public ModerationExecutor(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public enum Action { BAN, MUTE, KICK, WARN }

    /**
     * Подставляет {@code {player}}/{@code {duration}}/{@code {reason}} в шаблон команды из
     * конфига и выполняет её от лица консоли на основном потоке сервера. Безопасно вызывать с
     * любого потока (сама операция асинхронная).
     *
     * @param duration      только для BAN/MUTE - строка вида "1d2h", пусто/null = постоянно ("-1",
     *                      как принято у FlectonePulse). Игнорируется для остальных действий.
     * @param reason        причина; пусто/null = используется дефолтная причина самого FlectonePulse
     *                      (шаблон без {reason} просто не подставит плейсхолдер).
     * @param moderatorName кто реально выдал наказание (ник игрока/Discord-тег) - для лога в
     *                      {@code channels.moderation-log}, см. {@link ModerationLogPoller}.
     */
    public void execute(Action action, String player, String duration, String reason, String moderatorName) {
        String template = template(action);
        if (template == null || template.isBlank()) {
            plugin.getLogger().warning("/profile: команда для действия " + action + " не настроена (profile.commands."
                    + action.name().toLowerCase() + " пуста) - ничего не выполнено.");
            return;
        }

        String command = template
                .replace("{player}", player)
                .replace("{duration}", duration == null || duration.isBlank() ? "-1" : duration)
                .replace("{reason}", reason == null || reason.isBlank() ? "" : reason)
                .trim();

        if (moderatorName != null && !moderatorName.isBlank()) {
            RECENT_ATTRIBUTIONS.put(attributionKey(player, action), new Attribution(moderatorName, System.currentTimeMillis()));
        }

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    /**
     * Забирает (и удаляет) недавно запомненного реального инициатора для этого игрока+действия,
     * если запись не старше {@link #ATTRIBUTION_TTL_MS}. Вызывается {@link ModerationLogPoller}
     * при появлении новой строки fp_moderation с moderator = "Console".
     *
     * @param type строковое значение {@code fp_moderation.type} (BAN/MUTE/KICK/WARN).
     */
    static String consumeRecentAttribution(String player, String type) {
        Action action;
        try {
            action = Action.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Attribution attribution = RECENT_ATTRIBUTIONS.remove(attributionKey(player, action));
        if (attribution == null) return null;
        if (System.currentTimeMillis() - attribution.atMillis() > ATTRIBUTION_TTL_MS) return null;
        return attribution.moderatorName();
    }

    private static String attributionKey(String player, Action action) {
        return player.toLowerCase(Locale.ROOT) + ":" + action.name();
    }

    private String template(Action action) {
        return switch (action) {
            case BAN -> plugin.config().profileBanCommand();
            case MUTE -> plugin.config().profileMuteCommand();
            case KICK -> plugin.config().profileKickCommand();
            case WARN -> plugin.config().profileWarnCommand();
        };
    }
}
