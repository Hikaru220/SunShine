package com.sunshine.plugin.territory;

import java.util.UUID;

/**
 * Заявка на создание общины ИЛИ на расширение существующей (см. ТЗ раздел 3.4). Живёт в БД
 * (таблица {@code territory_applications}), а не только в памяти/Discord-сообщении - чтобы
 * переживать рестарт бота и не терять очередь на рассмотрении (см. ТЗ раздел 2).
 *
 * @param type               CREATE или EXPAND
 * @param targetCommunityId  null для CREATE, id существующей общины для EXPAND
 * @param proposedName       только для CREATE - null для EXPAND (имя общины не меняется)
 * @param proposedTag        только для CREATE - null для EXPAND (тег общины не меняется)
 * @param x1z1x2z2           предлагаемые (новые) границы территории
 */
public record CommunityApplication(int id, Type type, UUID applicantUuid, String applicantDiscordId,
                                    Integer targetCommunityId, String proposedName, String proposedTag,
                                    String world, int x1, int z1, int x2, int z2,
                                    String description, Status status,
                                    String adminChannelId, String adminMessageId,
                                    String decidedBy, Long decidedAtMs, String declineReason,
                                    long submittedAtMs) {

    public enum Type { CREATE, EXPAND }

    public enum Status { PENDING, APPROVED, REJECTED }

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

    /** "100x100" - размер сторон без умножения, см. Community.sizeLabel(). */
    public String sizeLabel() {
        return (maxX() - minX() + 1) + "x" + (maxZ() - minZ() + 1);
    }
}
