package com.sunshine.plugin.territory;

/**
 * Простой результат операции CommunityManager - значение либо человекочитаемая причина отказа
 * (показывается игроку/админу как есть в Discord/игре). Не исключение - ошибки вида "имя занято"
 * или "нет прав" это ожидаемые, частые исходы, а не сбой.
 */
public record TerritoryResult<T>(T value, String error) {

    public static <T> TerritoryResult<T> ok(T value) {
        return new TerritoryResult<>(value, null);
    }

    public static <T> TerritoryResult<T> fail(String error) {
        return new TerritoryResult<>(null, error);
    }

    public boolean isOk() {
        return error == null;
    }
}
