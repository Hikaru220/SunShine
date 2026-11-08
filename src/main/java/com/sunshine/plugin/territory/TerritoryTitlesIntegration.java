package com.sunshine.plugin.territory;

import com.sunshine.plugin.SunShinePlugin;
import com.territorytitles.plugin.TerritoryTitlesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.UUID;

/**
 * Мост к отдельному плагину TerritoryTitles (../TerritoryTitles) - зоны с title/subtitle при
 * входе игрока и базовой защитой блоков. SunShine размечает в нём зону при создании общины и
 * снимает разметку при её удалении/расформировании. TerritoryTitles - softdepend: если его нет на
 * сервере, методы этого класса просто ничего не делают (с предупреждением в лог), остальной
 * функционал SunShine не страдает.
 * <p>
 * Общины сознательно ограничены прямоугольником (2 противоположных угла), а не произвольным
 * 4-точечным полигоном - ради совместимости с TerritoryTitles (у него зоны - AABB) и чтобы не
 * писать отдельную геометрию проверки пересечения многоугольников (см. ТЗ, раздел 0/3.2).
 */
public final class TerritoryTitlesIntegration {

    /** Старый префикс имени зоны (до того, как попросили сделать имя зоны = имя общины) - нужен только для {@link #migrateLegacyZoneName}. */
    private static final String LEGACY_ZONE_PREFIX = "community-";

    private final SunShinePlugin plugin;

    public TerritoryTitlesIntegration(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    private TerritoryTitlesPlugin territoryTitles() {
        var found = Bukkit.getPluginManager().getPlugin("TerritoryTitles");
        return found instanceof TerritoryTitlesPlugin territoryTitlesPlugin ? territoryTitlesPlugin : null;
    }

    public boolean isAvailable() {
        return territoryTitles() != null;
    }

    /**
     * Создаёт/перезаписывает зону в TerritoryTitles по имени общины - title/subtitle показываются
     * игроку при входе на территорию, protectedZone включает блокировку ломать/строить чужим.
     * Высота зоны берётся на всю границу мира (см. дополнение в разделе 4.2 ТЗ про совпадение с
     * высотой BlueMap-маркера).
     *
     * @return true, если зона создана/обновлена; false, если TerritoryTitles не установлен или
     *         указанный мир не найден.
     */
    public boolean markCommunityZone(String communityName, String worldName, int x1, int z1, int x2, int z2,
                                      String enterTitle, String enterSubtitle, boolean protectedZone) {
        TerritoryTitlesPlugin territoryTitles = territoryTitles();
        if (territoryTitles == null) {
            plugin.getLogger().warning("TerritoryTitles не установлен - зона для общины '" + communityName + "' не размечена на карте.");
            return false;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Мир '" + worldName + "' не найден - зона для общины '" + communityName + "' не размечена.");
            return false;
        }

        Location pos1 = new Location(world, x1, world.getMinHeight(), z1);
        Location pos2 = new Location(world, x2, world.getMaxHeight(), z2);
        return territoryTitles.getZoneManager().createZone(zoneName(communityName), pos1, pos2, enterTitle, enterSubtitle, protectedZone);
    }

    /**
     * Снимает разметку зоны при удалении/расформировании общины. Тихо ничего не делает, если
     * TerritoryTitles не установлен или зоны и так не было.
     */
    public void unmarkCommunityZone(String communityName) {
        TerritoryTitlesPlugin territoryTitles = territoryTitles();
        if (territoryTitles == null) return;
        territoryTitles.getZoneManager().removeZone(zoneName(communityName));
    }

    /**
     * Закрывает/открывает территорию для посторонних (не участников общины) - отдельно от
     * protectedZone (та про ломание/постройку блоков). Персистится в TerritoryTitles.
     *
     * @return true, если применено; false, если TerritoryTitles не установлен или зоны ещё нет.
     */
    public boolean setClosed(String communityName, boolean closed) {
        TerritoryTitlesPlugin territoryTitles = territoryTitles();
        if (territoryTitles == null) return false;
        return territoryTitles.getZoneManager().setClosed(zoneName(communityName), closed);
    }

    /**
     * Кому закрытая территория всё равно открыта - вызывать при КАЖДОМ изменении состава общины
     * (добавили/исключили/передали владение), не только при создании - иначе список исключений в
     * TerritoryTitles разойдётся с реальными участниками. НЕ персистится на стороне TerritoryTitles
     * (см. Zone.exemptUuids) - источник правды это БД SunShine, довызывать этот метод при каждом
     * старте плагина для уже закрытых общин (иначе после рестарта TerritoryTitles список пуст).
     */
    public void setExemptMembers(String communityName, Collection<UUID> memberUuids) {
        TerritoryTitlesPlugin territoryTitles = territoryTitles();
        if (territoryTitles == null) return;
        territoryTitles.getZoneManager().setExempt(zoneName(communityName), memberUuids);
    }

    private String zoneName(String communityName) {
        return communityName;
    }

    /**
     * Разовая миграция для общин, созданных до того, как имя зоны стало = имени общины (раньше было
     * "community-Название") - удаляет старую запись зоны в TerritoryTitles под старым именем, чтобы
     * не плодить дубликаты после {@link #markCommunityZone} с новым именем. Вызывать один раз при
     * старте плагина для каждой уже существующей общины (см. {@code CommunityManager#resyncAllZonesOnStartup}).
     * Тихо ничего не делает, если TerritoryTitles не установлен или старой зоны уже не было.
     */
    public void migrateLegacyZoneName(String communityName) {
        TerritoryTitlesPlugin territoryTitles = territoryTitles();
        if (territoryTitles == null) return;
        territoryTitles.getZoneManager().removeZone(LEGACY_ZONE_PREFIX + communityName);
    }
}
