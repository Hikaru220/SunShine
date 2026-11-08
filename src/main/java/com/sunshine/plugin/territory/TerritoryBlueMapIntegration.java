package com.sunshine.plugin.territory;

import com.flowpowered.math.vector.Vector2d;
import com.sunshine.plugin.SunShinePlugin;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Опциональная отрисовка территорий на карте BlueMap (ТЗ раздел 4.2). Создаётся в
 * {@code SunShinePlugin.setupTerritory()} ТОЛЬКО если плагин BlueMap реально установлен на сервере
 * (см. комментарий в pom.xml) - никакой код здесь не выполняется, если BlueMap отсутствует, поэтому
 * отсутствие плагина не роняет остальной функционал территорий (ТЗ, softdepend).
 * <p>
 * Рисует плоский {@link ShapeMarker} (не {@code ExtrudeMarker}) на уровне моря мира - см.
 * дополнение в ТЗ про то, что полноразмерная экструзия от низа до верха мира визуально перекрывает
 * соседние территории. Высота берётся из {@code World#getSeaLevel()} - дешёвый запрос без загрузки
 * чанков, не точный рельеф, но этого достаточно для плоской заливки на карте.
 */
public final class TerritoryBlueMapIntegration {

    private static final String MARKER_SET_ID = "sunshine-territories";
    private static final String MARKER_SET_LABEL = "Территории";

    private final SunShinePlugin plugin;

    public TerritoryBlueMapIntegration(SunShinePlugin plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("Territory: мост к BlueMap создан, жду его готовности (BlueMapAPI.onEnable)...");
        // BlueMap мог загрузить свои карты позже, чем стартовал SunShine (или перезапуститься) -
        // при его собственном onEnable перерисовываем всё, что уже есть в кэше общин, один раз.
        // Если BlueMap УЖЕ готов на момент вызова onEnable() - коллбэк отработает немедленно (это
        // штатное поведение BlueMapAPI, а не "поймали позже").
        BlueMapAPI.onEnable(api -> {
            CommunityManager manager = plugin.communityManager();
            int count = manager == null ? 0 : manager.allCommunities().size();
            plugin.getLogger().info("Territory: BlueMap готов (" + api.getMaps().size() + " карт) - перерисовываю " + count + " общин.");
            if (manager == null) return;
            for (Community community : manager.allCommunities()) {
                markCommunity(community);
            }
        });
    }

    public void markCommunity(Community community) {
        boolean[] markedAny = {false};
        forEachMap(community.world(), map -> {
            markedAny[0] = true;
            float y = surfaceY(community.world());
            Shape shape = Shape.createRect(
                    new Vector2d(community.minX(), community.minZ()),
                    new Vector2d(community.maxX() + 1, community.maxZ() + 1));
            ShapeMarker marker = ShapeMarker.builder()
                    // .label() всплывает мелким тултипом только при НАВЕДЕНИИ - в попап по клику
                    // (тот, что реально видит игрок) попадает исключительно .detail(), поэтому
                    // название/тег дублируем туда же жирным первой строкой (BlueMap рендерит detail
                    // как HTML).
                    .label(community.tag() + " | " + community.name())
                    .shape(shape, y)
                    .fillColor(new Color(87, 242, 135, 0.35f))
                    .lineColor(new Color(87, 242, 135, 0.85f))
                    .lineWidth(2)
                    .detail("<b>" + community.tag() + " | " + community.name() + "</b><br>Владелец: " + safeName(community.ownerUuid()))
                    .centerPosition()
                    .build();
            markerSet(map).put(markerId(community.name()), marker);
            plugin.getLogger().info("Territory: маркер '" + community.name() + "' поставлен на карте '" + map.getId() + "'.");
        });
        if (!markedAny[0]) {
            plugin.getLogger().warning("Territory: не удалось разметить '" + community.name()
                    + "' на BlueMap - см. предупреждение выше (мир/карты не нашлись) или BlueMap ещё не готов.");
        }
    }

    /** Снимает маркер при удалении/расформировании общины - вызывать до того, как её координаты станут недоступны (ТЗ, чек-лист удаления в разделе 4.1). */
    public void unmarkCommunity(String communityName, String worldName) {
        forEachMap(worldName, map -> markerSet(map).remove(markerId(communityName)));
    }

    private void forEachMap(String worldName, Consumer<BlueMapMap> action) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Territory/BlueMap: мир '" + worldName + "' не найден на сервере (Bukkit.getWorld).");
            return;
        }
        var apiOpt = BlueMapAPI.getInstance();
        if (apiOpt.isEmpty()) {
            plugin.getLogger().warning("Territory/BlueMap: BlueMapAPI.getInstance() пуст - BlueMap ещё не готов (карты не загружены). "
                    + "Должно само подхватиться позже через BlueMapAPI.onEnable().");
            return;
        }
        var worldOpt = apiOpt.get().getWorld(world);
        if (worldOpt.isEmpty()) {
            plugin.getLogger().warning("Territory/BlueMap: BlueMap не распознал мир '" + worldName
                    + "' (getWorld(Bukkit.World) вернул пусто) - проверь, что этот мир вообще подключён в конфиге BlueMap.");
            return;
        }
        int mapCount = worldOpt.get().getMaps().size();
        if (mapCount == 0) {
            plugin.getLogger().warning("Territory/BlueMap: у мира '" + worldName + "' в BlueMap 0 карт (getMaps() пуст).");
            return;
        }
        for (BlueMapMap map : worldOpt.get().getMaps()) {
            action.accept(map);
        }
    }

    private MarkerSet markerSet(BlueMapMap map) {
        return map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, id -> MarkerSet.builder().label(MARKER_SET_LABEL).build());
    }

    private String markerId(String communityName) {
        return "community-" + communityName;
    }

    /**
     * BlueMap рисует ShapeMarker как часть 3D-сцены на конкретной высоте - если она ниже деревьев/
     * рельефа, маркер частично перекрывается ими (именно это давало "кривые" очертания на скриншоте
     * в холмистой местности). Раньше здесь был уровень моря - слишком низко для леса/гор. Берём
     * потолок мира минус небольшой отступ - деревья и постройки почти никогда не достают до лимита
     * высоты, а сам вызов дешёвый (константа мира, без загрузки чанков).
     */
    private float surfaceY(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? 320f : world.getMaxHeight() - 5f;
    }

    private String safeName(UUID uuid) {
        String name = plugin.linkManager().nameFor(uuid);
        return name == null ? uuid.toString() : name;
    }
}
