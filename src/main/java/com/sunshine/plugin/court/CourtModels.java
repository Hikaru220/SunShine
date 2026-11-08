package com.sunshine.plugin.court;

import java.util.UUID;

/** Записи БД суда и ссылка на игрока. */
public final class CourtModels {

    private CourtModels() {
    }

    /** status: FILED / ACCEPTED / SCHEDULED / HEARING / VERDICT / CLOSED / REJECTED; verdict: GUILTY / ACQUITTED / SETTLED / OVERTURNED; appeal: null / PENDING / GRANTED / DENIED. */
    public record CourtCase(int id, String plaintiffDiscord, String plaintiffName, String defendantUuid,
                            String defendantName, String defendantDiscord, String title, String claim,
                            String evidence, String status, String judgeDiscord, Long hearingAt, String channelId,
                            String verdict, String verdictText, Long verdictAt, String appeal, long createdAt) {
    }

    /** status: PENDING / PROCESSING / PAID / OVERDUE / CANCELLED. */
    public record Fine(int id, Integer caseId, String targetUuid, String targetName, String targetDiscord,
                       long amount, String reason, String issuedBy, String status, long createdAt, long deadline,
                       boolean reminded, Long paidAt, String appeal) {
    }

    /** type: BAN / MUTE / KICK / WARN; status: ACTIVE / REVOKED. */
    public record Decree(int id, Integer caseId, String targetUuid, String targetName, String type,
                         String duration, String reason, String issuedBy, String status, long createdAt) {
    }

    public record PlayerRef(UUID uuid, String name, String discordId) {
    }
}
