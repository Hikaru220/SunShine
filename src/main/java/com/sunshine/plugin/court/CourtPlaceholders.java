package com.sunshine.plugin.court;

import com.sunshine.plugin.court.CourtService.RoleTag;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI-плейсхолдеры судей для чата и таба (идентификатор ОДНИМ словом - PlaceholderAPI режет
 * плейсхолдер по первому подчёркиванию):
 * <ul>
 *   <li>{@code %sunshinecourt_suffix%} - " ⚖" после ника, только у судьи, у которого УЖЕ есть префикс (ранг);</li>
 *   <li>{@code %sunshinecourt_prefix%} - "⚖ " перед ником, только у судьи БЕЗ префикса;</li>
 *   <li>{@code %sunshinecourt_mark%} - просто знак (⚖), у судьи и главного судьи свой цвет;</li>
 *   <li>{@code %sunshinecourt_role%} - "Судья" / "Главный судья".</li>
 * </ul>
 * У не-судей всегда пусто. Читает кэш ролей {@link CourtService#roleTagFor}, а не Discord - вызывается
 * синхронно на каждую отрисовку чата/таба.
 */
public final class CourtPlaceholders extends PlaceholderExpansion {

    private final CourtSettings settings;
    private final CourtService service;

    public CourtPlaceholders(CourtSettings settings, CourtService service) {
        this.settings = settings;
        this.service = service;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "sunshinecourt";
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
        RoleTag tag = service.roleTagFor(player.getUniqueId());
        if (tag == RoleTag.NONE) return "";

        String mark = ChatColor.translateAlternateColorCodes('&', tag == RoleTag.CHIEF ? settings.tagChief() : settings.tagJudge()) + "§r";
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "mark" -> mark;
            case "suffix" -> hasPrefix(player) ? " " + mark : "";
            case "prefix" -> hasPrefix(player) ? "" : mark + " ";
            case "role" -> tag == RoleTag.CHIEF ? "Главный судья" : "Судья";
            default -> null;
        };
    }

    /** Есть ли у игрока префикс (по плейсхолдеру court.tags.prefix-placeholder, обычно LuckPerms). Пустой плейсхолдер в конфиге = префикса нет ни у кого. */
    private boolean hasPrefix(OfflinePlayer player) {
        String placeholder = settings.tagPrefixPlaceholder();
        if (placeholder.isBlank()) return false;
        String resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
        String plain = resolved.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").replaceAll("§x(§[0-9a-fA-F]){6}", "").trim();
        return !plain.isEmpty() && !resolved.equals(placeholder);
    }
}
