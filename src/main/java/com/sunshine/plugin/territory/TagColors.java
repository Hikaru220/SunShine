package com.sunshine.plugin.territory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Палитра цветов клан-тега, которую может выбрать владелец общины. Ключ - однобуквенный код
 * ванильного Minecraft-цвета (легаси '§'-формат) - работает без доп. настройки и в чате
 * (FlectonePulse проксирует значение плейсхолдера как есть), и в табе (TAB понимает '§'/'&' коды
 * из коробки), в отличие от MiniMessage-тегов или RGB-hex, которые не все консьюмеры плейсхолдеров
 * одинаково парсят.
 */
public final class TagColors {

    public static final String DEFAULT_CODE = "f";

    private static final Map<String, String> PALETTE = new LinkedHashMap<>();
    static {
        PALETTE.put("f", "Белый (по умолчанию)");
        PALETTE.put("c", "Красный");
        PALETTE.put("4", "Тёмно-красный");
        PALETTE.put("6", "Оранжевый");
        PALETTE.put("e", "Жёлтый");
        PALETTE.put("a", "Зелёный");
        PALETTE.put("2", "Тёмно-зелёный");
        PALETTE.put("b", "Бирюзовый");
        PALETTE.put("9", "Синий");
        PALETTE.put("1", "Тёмно-синий");
        PALETTE.put("d", "Розовый");
        PALETTE.put("5", "Фиолетовый");
        PALETTE.put("7", "Серый");
    }

    private TagColors() {
    }

    public static boolean isValid(String code) {
        return PALETTE.containsKey(code);
    }

    public static Map<String, String> palette() {
        return PALETTE;
    }

    /** "§c" и т.п. - вставляется перед тегом в игровых плейсхолдерах (см. {@link Community#coloredTag()}). */
    public static String toChatCode(String colorCode) {
        return "§" + (colorCode == null || !isValid(colorCode) ? DEFAULT_CODE : colorCode);
    }
}
