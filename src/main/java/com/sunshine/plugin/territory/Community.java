package com.sunshine.plugin.territory;

import java.util.Set;
import java.util.UUID;

/**
 * Община/территория. Прямоугольник (2 угла x1,z1 - x2,z2) на всю высоту мира - не произвольный
 * полигон, ради совместимости с TerritoryTitles и простоты проверки пересечений (см. ТЗ, раздел 0.1).
 *
 * @param tag           клан-тег (2-6 символов, уникален как и name) - см. ТЗ раздел 3.5
 * @param restrictions  админ-ограничения: любые из "lock"/"pvp_forced"/"no_adds" (раздел 5 ТЗ)
 * @param closed        закрыта ли территория для посещения посторонним (не участникам) - решает
 *                       владелец из своей панели, отдельно от админских restrictions. Зеркалится в
 *                       TerritoryTitles через {@link TerritoryTitlesIntegration#setClosed} и
 *                       {@link TerritoryTitlesIntegration#setExemptMembers} при любом изменении
 *                       состава участников.
 * @param tagColor      однобуквенный код цвета клан-тега (см. {@link TagColors}) - владелец выбирает
 *                       в своей панели, влияет только на отображение в игре (см. {@link #coloredTag()}).
 * @param tagBold       жирный ли клан-тег - переключается там же, независимо от цвета.
 */
public record Community(int id, String name, String tag, UUID ownerUuid, String world,
                         int x1, int z1, int x2, int z2, Set<String> restrictions, boolean closed,
                         String bluemapId, long createdAtMs, String tagColor, boolean tagBold) {

    public boolean isLocked() {
        return restrictions.contains("lock");
    }

    public boolean isPvpForced() {
        return restrictions.contains("pvp_forced");
    }

    public boolean addsBlocked() {
        return restrictions.contains("no_adds");
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public long area() {
        return (long) (maxX() - minX() + 1) * (long) (maxZ() - minZ() + 1);
    }

    /** "100x100" - размер сторон без умножения (площадь путает: непонятно, что 10000 это 100х100, а не что-то другое). */
    public String sizeLabel() {
        return (maxX() - minX() + 1) + "x" + (maxZ() - minZ() + 1);
    }

    public boolean contains(String worldName, int x, int z) {
        return world.equals(worldName) && x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /** Пересекается ли с прямоугольником (ox1,oz1)-(ox2,oz2) в том же мире. Для заявок на расширение (ТЗ 3.4) собственная старая территория из сравнения исключается вызывающим кодом. */
    public boolean overlaps(String otherWorld, int ox1, int oz1, int ox2, int oz2) {
        if (!world.equals(otherWorld)) return false;
        int oMinX = Math.min(ox1, ox2), oMaxX = Math.max(ox1, ox2);
        int oMinZ = Math.min(oz1, oz2), oMaxZ = Math.max(oz1, oz2);
        return !(maxX() < oMinX || minX() > oMaxX || maxZ() < oMinZ || minZ() > oMaxZ);
    }

    /** Форматированный ник участника с тегом - "ТЕГ | ник" (ТЗ раздел 3.5). */
    public String formatMemberName(String minecraftName) {
        return tag + " | " + minecraftName;
    }

    /**
     * Тег с легаси-цветкодом (и жирностью) для игровых плейсхолдеров (не для Discord - там цвет не
     * поддерживается). Заканчивается "§r" - иначе цвет/жирность "протекут" на всё, что идёт следом
     * в шаблоне чата/таба (ник, разделитель и т.д.), т.к. легаси-коды форматирования не сбрасываются
     * сами по себе.
     */
    public String coloredTag() {
        return TagColors.toChatCode(tagColor) + (tagBold ? "§l" : "") + tag + "§r";
    }
}
