package com.sunshine.plugin.application;

public class Application {

    private final String discordId;
    private final String discordTag;
    private final String minecraftName;
    private final String age;
    private final String source;
    private final String about;
    private final String rulesAccepted;
    private final long submittedAt;

    private ApplicationStatus status;
    private String decidedBy;
    private boolean joinRoleGranted;
    private String declineReason;
    /** Чем игрок планирует заниматься на сервере (ответ из анкеты); у старых заявок пусто. */
    private String plans = "";
    /** ID приватного канала заявки в Discord (null у старых заявок и при выключенных каналах заявок). */
    private String channelId;

    public Application(String discordId, String discordTag, String minecraftName, String age,
                        String source, String about, String rulesAccepted, long submittedAt,
                        ApplicationStatus status, String decidedBy) {
        this.discordId = discordId;
        this.discordTag = discordTag;
        this.minecraftName = minecraftName;
        this.age = age;
        this.source = source;
        this.about = about;
        this.rulesAccepted = rulesAccepted;
        this.submittedAt = submittedAt;
        this.status = status;
        this.decidedBy = decidedBy;
    }

    public String discordId() {
        return discordId;
    }

    public String discordTag() {
        return discordTag;
    }

    public String minecraftName() {
        return minecraftName;
    }

    public String age() {
        return age;
    }

    public String source() {
        return source;
    }

    public String about() {
        return about;
    }

    public String rulesAccepted() {
        return rulesAccepted;
    }

    public long submittedAt() {
        return submittedAt;
    }

    public ApplicationStatus status() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public boolean joinRoleGranted() {
        return joinRoleGranted;
    }

    public void setJoinRoleGranted(boolean joinRoleGranted) {
        this.joinRoleGranted = joinRoleGranted;
    }

    public String plans() {
        return plans;
    }

    public void setPlans(String plans) {
        this.plans = plans == null ? "" : plans;
    }

    public String channelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String declineReason() {
        return declineReason;
    }

    public void setDeclineReason(String declineReason) {
        this.declineReason = declineReason;
    }
}
