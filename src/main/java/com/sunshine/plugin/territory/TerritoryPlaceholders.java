package com.sunshine.plugin.territory;

import com.sunshine.plugin.SunShinePlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI-плейсхолдеры общины игрока (ТЗ раздел 3.5) - для игрового чата/таблиста, которые
 * форматирует FlectonePulse (SunShine его формат не переопределяет, только отдаёт данные):
 * <ul>
 *   <li>{@code %sunshineterritory_tag%} - голый тег ("ASL"), пусто если общины нет;</li>
 *   <li>{@code %sunshineterritory_prefix%} - тег + разделитель ("ASL | "), готов к вставке перед ником;</li>
 *   <li>{@code %sunshineterritory_name%} - полное название общины.</li>
 * </ul>
 * Идентификатор ОДНИМ словом, без "_" внутри (не "sunshine_territory") - PlaceholderAPI режет
 * плейсхолдер по ПЕРВОМУ подчёркиванию, чтобы отделить идентификатор расширения от параметра;
 * с подчёркиванием внутри самого идентификатора он не находит совпадение и оставляет текст
 * плейсхолдера как есть, нерасшифрованным (реально словленный баг - см. чат/таб в игре).
 * <p>
 * Читает {@link CommunityManager#findCommunityForPlayerCached}, а не БД напрямую - PlaceholderAPI
 * вызывает {@link #onRequest} синхронно (часто на основном потоке при каждой отрисовке чата/
 * таблиста), блокирующий JDBC-запрос здесь недопустим.
 */
public final class TerritoryPlaceholders extends PlaceholderExpansion {

    private final SunShinePlugin plugin;

    public TerritoryPlaceholders(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "sunshineterritory";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SunShine";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        CommunityManager manager = plugin.communityManager();
        Community community = manager == null ? null : manager.findCommunityForPlayerCached(player.getUniqueId());
        if (community == null) return "";

        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "tag" -> community.coloredTag();
            case "prefix" -> community.coloredTag() + " | ";
            case "name" -> community.name();
            default -> null;
        };
    }
}
