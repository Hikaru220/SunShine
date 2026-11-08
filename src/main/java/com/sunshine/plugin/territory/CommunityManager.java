package com.sunshine.plugin.territory;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Бизнес-логика общин/территорий - валидация, создание/одобрение заявок, управление составом,
 * ограничения (ТЗ разделы 2-6). Чистая Java, без JDA/Bukkit-событий - вызывается из Discord-слушателей
 * и слеш-команд (ещё не написаны), которые сами решают, кому что разрешено на уровне Discord-ролей.
 * <p>
 * Все публичные методы делают блокирующие JDBC-запросы - вызывающий код обязан звать их с
 * асинхронного потока (как везде в этом проекте - см. {@code Bukkit.getScheduler().runTaskAsynchronously}).
 * <p>
 * Держит лёгкий кэш общин в памяти (обновляется только при изменениях, не по таймеру) для быстрой
 * проверки "какая община в этой точке" на будущих Bukkit-слушателях (pvp_forced и т.п.) - без кэша
 * это был бы SELECT на каждое разрушение блока/удар, что как раз то, от чего предостерегает ТЗ
 * (раздел 3.3, про экономию ресурсов).
 */
public final class CommunityManager {

    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{L}0-9 _-]{2,64}$");

    private final SunShinePlugin plugin;
    private final TerritoryDatabase db;
    private volatile List<Community> cache = List.of();
    /** uuid участника -> его община - для PlaceholderAPI (TerritoryPlaceholders), который дёргается синхронно на каждую отрисовку чата/таблиста и не может ждать JDBC. */
    private volatile Map<UUID, Community> memberIndex = Map.of();
    /** Discord ID -> община: запасной поиск для игрока, чей UUID не записан в общину, но другой аккаунт того же человека записан. */
    private volatile Map<String, Community> discordIndex = Map.of();

    public CommunityManager(SunShinePlugin plugin, TerritoryDatabase db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** Перечитывает все общины из БД в кэш - вызывать при старте плагина и после любой мутации. Заодно триггерит обновление #список-общин (ТЗ 4.3), если канал настроен. */
    public void refreshCache() {
        try {
            cache = db.listAllCommunities();
            Map<Integer, Community> byId = cache.stream().collect(java.util.stream.Collectors.toMap(Community::id, c -> c));
            Map<UUID, Community> newIndex = new java.util.HashMap<>();
            for (Map.Entry<UUID, Integer> entry : db.allMemberships().entrySet()) {
                Community c = byId.get(entry.getValue());
                if (c != null) newIndex.put(entry.getKey(), c);
            }
            memberIndex = newIndex;

            // Тег должен идти за Discord-аккаунтом, а не за одним UUID: у человека бывает несколько
            // привязанных Minecraft-аккаунтов, а плагин записывает в общину лишь один из них.
            Map<String, Community> newDiscordIndex = new java.util.HashMap<>();
            for (Map.Entry<UUID, Community> entry : newIndex.entrySet()) {
                String discordId = plugin.linkManager().discordIdFor(entry.getKey());
                if (discordId != null && !discordId.isBlank()) newDiscordIndex.putIfAbsent(discordId, entry.getValue());
            }
            discordIndex = newDiscordIndex;
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось обновить кэш общин: " + e.getMessage());
        }
        if (plugin.territoryListChannelUpdater() != null) {
            plugin.territoryListChannelUpdater().refresh(cache);
        }
    }

    /** Текущий кэш общин (уже отсортирован по имени, см. {@link TerritoryDatabase#listAllCommunities}) - без похода в БД. */
    public List<Community> allCommunities() {
        return cache;
    }

    /**
     * Разовая пересинхронизация зон в TerritoryTitles при старте плагина - чинит уже существующие
     * общины под старым именем зоны ("community-Название") на новое (просто "Название"), плюс
     * досылает exempt-список для уже закрытых территорий (после рестарта TerritoryTitles его не
     * помнит - источник правды всегда БД SunShine). Звать один раз сразу после {@link #refreshCache()}
     * при старте (см. {@code SunShinePlugin.setupTerritory()}).
     */
    public void resyncAllZonesOnStartup() {
        for (Community community : cache) {
            plugin.territoryTitlesIntegration().migrateLegacyZoneName(community.name());
            markZoneAndSyncState(community);
        }
    }

    /**
     * Быстрый некорректный-по-свежести, но НЕБЛОКИРУЮЩИЙ поиск по id - по кэшу, без JDBC. Нужен
     * именно там, где Discord ждёт немедленный ответ (например, открыть модалку на клик по кнопке -
     * до неё нельзя ничего deferReply()'ить, а значит и ждать блокирующий запрос к БД тоже нельзя,
     * иначе ровно то же "приложение не ответило вовремя", что уже ловили на других кнопках).
     */
    public Community getCached(int id) {
        for (Community c : cache) {
            if (c.id() == id) return c;
        }
        return null;
    }

    /** Быстрая проверка "какая община в этой точке" - по кэшу, без похода в БД (см. класс-комментарий). */
    public Community findCommunityAt(String world, int x, int z) {
        for (Community c : cache) {
            if (c.contains(world, x, z)) return c;
        }
        return null;
    }

    /** То же самое, что {@link #findCommunityForPlayer}, но по кэшу и без блокировки - для PlaceholderAPI (может немного отставать от реального состояния, обновляется при каждой мутации). */
    public Community findCommunityForPlayerCached(UUID uuid) {
        Community direct = memberIndex.get(uuid);
        if (direct != null) return direct;
        String discordId = plugin.linkManager().discordIdFor(uuid);
        return discordId == null || discordId.isBlank() ? null : discordIndex.get(discordId);
    }

    public Community findCommunityForPlayer(UUID uuid) {
        try {
            return db.getCommunityForPlayer(uuid);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось найти общину игрока " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    public Community findCommunityByName(String name) {
        try {
            return db.getCommunityByName(name);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось найти общину '" + name + "': " + e.getMessage());
            return null;
        }
    }

    public List<UUID> members(int communityId) {
        try {
            return db.getMembers(communityId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось прочитать участников общины #" + communityId + ": " + e.getMessage());
            return List.of();
        }
    }

    // ---------- Заявки (ТЗ 3.2/3.3/3.4) ----------

    public TerritoryResult<Integer> submitCreateApplication(UUID applicantUuid, String discordId,
                                                              String name, String tag,
                                                              int x1, int z1, int x2, int z2, String description) {
        TerritoryResult<Void> nameTagCheck = validateNameAndTag(name, tag);
        if (!nameTagCheck.isOk()) return TerritoryResult.fail(nameTagCheck.error());

        TerritoryResult<Void> preflight = preflightApplicant(applicantUuid);
        if (!preflight.isOk()) return TerritoryResult.fail(preflight.error());

        TerritoryResult<Void> boundsCheck = validateBounds(x1, z1, x2, z2);
        if (!boundsCheck.isOk()) return TerritoryResult.fail(boundsCheck.error());

        String world = plugin.config().territoryWorld();
        try {
            if (db.nameOrTagTaken(name, tag)) {
                return TerritoryResult.fail("Название или тег уже заняты другой общиной.");
            }
            if (!db.findOverlapping(world, x1, z1, x2, z2, null).isEmpty()) {
                return TerritoryResult.fail("Эти границы пересекаются с уже существующей территорией.");
            }
            int id = db.insertApplication(CommunityApplication.Type.CREATE, applicantUuid, discordId,
                    null, name, tag, world, x1, z1, x2, z2, description);
            return TerritoryResult.ok(id);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось подать заявку на создание: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Integer> submitExpandApplication(UUID ownerUuid, int communityId,
                                                              int x1, int z1, int x2, int z2, String description) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) {
            return TerritoryResult.fail("Только владелец может подать заявку на расширение.");
        }

        TerritoryResult<Void> preflight = preflightApplicant(ownerUuid);
        if (!preflight.isOk()) return TerritoryResult.fail(preflight.error());

        TerritoryResult<Void> boundsCheck = validateBounds(x1, z1, x2, z2);
        if (!boundsCheck.isOk()) return TerritoryResult.fail(boundsCheck.error());

        String world = plugin.config().territoryWorld();
        String discordId = plugin.linkManager().discordIdFor(ownerUuid);
        try {
            if (!db.findOverlapping(world, x1, z1, x2, z2, communityId).isEmpty()) {
                return TerritoryResult.fail("Эти границы пересекаются с уже существующей территорией.");
            }
            int id = db.insertApplication(CommunityApplication.Type.EXPAND, ownerUuid, discordId,
                    communityId, null, null, world, x1, z1, x2, z2, description);
            return TerritoryResult.ok(id);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось подать заявку на расширение: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Community> approveApplication(int applicationId, String decidedByName) {
        CommunityApplication application;
        try {
            application = db.getApplication(applicationId);
        } catch (SQLException e) {
            return TerritoryResult.fail("Внутренняя ошибка чтения заявки.");
        }
        if (application == null) return TerritoryResult.fail("Заявка не найдена.");
        if (application.status() != CommunityApplication.Status.PENDING) {
            return TerritoryResult.fail("Заявка уже обработана.");
        }

        Integer excludeId = application.type() == CommunityApplication.Type.EXPAND ? application.targetCommunityId() : null;
        try {
            if (!db.findOverlapping(application.world(), application.x1(), application.z1(), application.x2(), application.z2(), excludeId).isEmpty()) {
                return TerritoryResult.fail("Границы теперь пересекаются с другой территорией, одобренной позже подачи - нужно отклонить и попросить подать заново.");
            }

            Community community;
            if (application.type() == CommunityApplication.Type.CREATE) {
                int id = db.createCommunity(application.proposedName(), application.proposedTag(), application.applicantUuid(),
                        application.world(), application.x1(), application.z1(), application.x2(), application.z2());
                community = db.getCommunity(id);
            } else {
                db.updateCommunityBounds(application.targetCommunityId(), application.world(),
                        application.x1(), application.z1(), application.x2(), application.z2());
                community = db.getCommunity(application.targetCommunityId());
            }

            db.decideApplication(applicationId, CommunityApplication.Status.APPROVED, decidedByName, null);
            refreshCache();
            markZoneAndSyncState(community);
            if (application.type() == CommunityApplication.Type.CREATE) {
                resyncNickname(application.applicantUuid()); // владелец только что получил тег - обновляем ник сразу, не ждём переподключения
            }
            logAdminAction(application.type() == CommunityApplication.Type.CREATE ? "✅ Одобрено создание" : "✅ Одобрено расширение",
                    community.name(), decidedByName, null);
            return TerritoryResult.ok(community);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось одобрить заявку #" + applicationId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Void> rejectApplication(int applicationId, String decidedByName, String reason) {
        try {
            CommunityApplication application = db.getApplication(applicationId);
            if (application == null) return TerritoryResult.fail("Заявка не найдена.");
            if (application.status() != CommunityApplication.Status.PENDING) {
                return TerritoryResult.fail("Заявка уже обработана.");
            }
            db.decideApplication(applicationId, CommunityApplication.Status.REJECTED, decidedByName, reason);
            String communityLabel = application.type() == CommunityApplication.Type.CREATE
                    ? application.proposedName() : "#" + application.targetCommunityId();
            logAdminAction("❌ Отклонена заявка", communityLabel, decidedByName, reason);
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось отклонить заявку #" + applicationId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    // ---------- Состав общины (ТЗ 4.1) ----------

    /**
     * Проверяет, можно ли пригласить игрока, БЕЗ добавления - для шага "спроси согласие" (ТЗ 4.1:
     * приглашение с подтверждением, а не мгновенное добавление). Сама запись в БД - {@link #addMember}.
     */
    public TerritoryResult<Community> canInvite(int communityId, UUID ownerUuid, UUID inviteeUuid) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) return TerritoryResult.fail("Только владелец может приглашать игроков.");
        if (community.addsBlocked()) return TerritoryResult.fail("Администрация временно заблокировала приглашения в эту общину.");
        if (community.ownerUuid().equals(inviteeUuid)) return TerritoryResult.fail("Ты уже владелец этой общины.");
        if (findCommunityForPlayer(inviteeUuid) != null) return TerritoryResult.fail("Этот игрок уже состоит в другой общине.");
        return TerritoryResult.ok(community);
    }

    /** Коммитит приглашение после согласия игрока (см. {@link #canInvite}) - перепроверяет те же условия на случай, если что-то изменилось за время ожидания ответа. */
    public TerritoryResult<Void> addMember(int communityId, UUID ownerUuid, UUID inviteeUuid) {
        TerritoryResult<Community> check = canInvite(communityId, ownerUuid, inviteeUuid);
        if (!check.isOk()) return TerritoryResult.fail(check.error());
        Community community = check.value();

        try {
            db.addMember(communityId, inviteeUuid);
            refreshCache();
            pushMemberSync(community);
            resyncNickname(inviteeUuid);
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось добавить участника: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Void> kickMember(int communityId, UUID ownerUuid, UUID targetUuid) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) return TerritoryResult.fail("Только владелец может исключать игроков.");
        return removeMemberInternal(community, targetUuid, "Владельца нельзя исключить - сначала передай права или удали общину.");
    }

    /** Админский кик - без проверки "ты владелец", доступ контролирует вызывающий Discord-слой (роль администратора). */
    public TerritoryResult<Void> adminKickMember(int communityId, UUID targetUuid, String actorName) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        TerritoryResult<Void> result = removeMemberInternal(community, targetUuid, "Владельца нельзя исключить - сначала передай права.");
        if (result.isOk()) {
            logAdminAction("➖ Админ-кик", community.name(), actorName, "Игрок: " + safeName(targetUuid));
        }
        return result;
    }

    private TerritoryResult<Void> removeMemberInternal(Community community, UUID targetUuid, String ownerBlockedMessage) {
        if (community.ownerUuid().equals(targetUuid)) return TerritoryResult.fail(ownerBlockedMessage);
        try {
            db.removeMember(community.id(), targetUuid);
            refreshCache();
            pushMemberSync(community);
            resyncNickname(targetUuid);
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось исключить участника: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Void> leaveCommunity(UUID uuid) {
        Community community = findCommunityForPlayer(uuid);
        UUID memberUuid = uuid;
        if (community == null) {
            // Тег идёт за Discord-аккаунтом (см. findCommunityForPlayerCached), поэтому и выйти можно с любого
            // аккаунта того же человека, даже если в общину записан только другой из них.
            String discordId = plugin.linkManager().discordIdFor(uuid);
            if (discordId != null && !discordId.isBlank()) {
                for (UUID other : plugin.linkManager().uuidsForDiscordId(discordId)) {
                    Community found = findCommunityForPlayer(other);
                    if (found != null) {
                        community = found;
                        memberUuid = other;
                        break;
                    }
                }
            }
        }
        if (community == null) return TerritoryResult.fail("Ты не состоишь в общине.");
        if (community.ownerUuid().equals(memberUuid)) {
            return TerritoryResult.fail("Владелец не может просто выйти - сначала передай права другому участнику или удали общину.");
        }
        try {
            db.removeMember(community.id(), memberUuid);
            refreshCache();
            pushMemberSync(community);
            resyncNickname(uuid);
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось выйти из общины: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Void> transferOwnership(int communityId, UUID currentOwnerUuid, UUID newOwnerUuid) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(currentOwnerUuid)) return TerritoryResult.fail("Только текущий владелец может передать права.");
        return adminTransferOwnership(communityId, newOwnerUuid, safeName(currentOwnerUuid));
    }

    /** Админская принудительная передача владения - без проверки "ты владелец" (ТЗ раздел 5, /admin_terra transfer). */
    public TerritoryResult<Void> adminTransferOwnership(int communityId, UUID newOwnerUuid, String actorName) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        try {
            List<UUID> members = db.getMembers(communityId);
            if (!members.contains(newOwnerUuid)) {
                db.addMember(communityId, newOwnerUuid);
            }
            db.setOwner(communityId, newOwnerUuid);
            refreshCache();
            logAdminAction("👑 Передача владения", community.name(), actorName, "Новый владелец: " + safeName(newOwnerUuid));
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось передать владение: " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    // ---------- Удаление (ТЗ 4.1 - точный чек-лист) ----------

    public TerritoryResult<Void> deleteCommunity(int communityId, UUID requesterUuid, boolean isAdmin, String actorName) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!isAdmin && !community.ownerUuid().equals(requesterUuid)) {
            return TerritoryResult.fail("Только владелец может удалить общину.");
        }
        List<UUID> formerMembers = members(communityId); // захватываем до удаления - каскад снесёт их из territory_members
        try {
            db.deleteCommunity(communityId);
            refreshCache();
            for (UUID member : formerMembers) {
                resyncNickname(member); // у всех бывших участников пропадает тег из ника - не ждём переподключения
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось удалить общину #" + communityId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
        // БД - источник правды, поэтому запись уже удалена, даже если разметка карты ниже не удастся
        // (TerritoryTitles/BlueMap временно недоступны, ТЗ раздел 7.3) - логируем и продолжаем.
        plugin.territoryTitlesIntegration().unmarkCommunityZone(community.name());
        if (plugin.territoryBlueMapIntegration() != null) {
            plugin.territoryBlueMapIntegration().unmarkCommunity(community.name(), community.world());
        }
        logAdminAction(isAdmin ? "🗑 Админ-удаление" : "🗑 Удаление владельцем", community.name(),
                actorName == null || actorName.isBlank() ? safeName(requesterUuid) : actorName, null);
        return TerritoryResult.ok(null);
    }

    // ---------- Цвет/жирность клан-тега ----------

    public TerritoryResult<Void> setTagColor(int communityId, UUID ownerUuid, String colorCode) {
        if (!TagColors.isValid(colorCode)) return TerritoryResult.fail("Неизвестный цвет.");
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) return TerritoryResult.fail("Только владелец может это менять.");
        try {
            db.setTagColor(communityId, colorCode);
            refreshCache();
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось изменить цвет тега для общины #" + communityId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    public TerritoryResult<Void> setTagBold(int communityId, UUID ownerUuid, boolean bold) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) return TerritoryResult.fail("Только владелец может это менять.");
        try {
            db.setTagBold(communityId, bold);
            refreshCache();
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось изменить жирность тега для общины #" + communityId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    // ---------- Ограничения и закрытая территория (ТЗ разделы 3.6/5) ----------

    public TerritoryResult<Void> setClosed(int communityId, UUID ownerUuid, boolean closed) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        if (!community.ownerUuid().equals(ownerUuid)) return TerritoryResult.fail("Только владелец может это менять.");
        try {
            db.setClosed(communityId, closed);
            refreshCache();
            plugin.territoryTitlesIntegration().setClosed(community.name(), closed);
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось изменить closed для общины #" + communityId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    /** Админ-ограничения (lock/pvp_forced/no_adds) - ТЗ раздел 5. Пустой набор = все ограничения сняты. */
    public TerritoryResult<Void> adminSetRestrictions(int communityId, Set<String> restrictions, String actorName) {
        Community community = findCommunityByIdInternal(communityId);
        if (community == null) return TerritoryResult.fail("Община не найдена.");
        try {
            db.setRestrictions(communityId, restrictions);
            refreshCache();
            Community updated = db.getCommunity(communityId);
            // lock зеркалится в protectedZone TerritoryTitles - перемечаем зону тем же способом,
            // что и при создании/расширении (title/subtitle не меняются).
            markZoneAndSyncState(updated);
            logAdminAction("⚠ Ограничения изменены", community.name(), actorName,
                    restrictions.isEmpty() ? "Сняты все ограничения" : "Активны: " + String.join(", ", restrictions));
            return TerritoryResult.ok(null);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось изменить ограничения общины #" + communityId + ": " + e.getMessage());
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
    }

    // ---------- Внутреннее ----------

    private Community findCommunityByIdInternal(int id) {
        try {
            return db.getCommunity(id);
        } catch (SQLException e) {
            plugin.getLogger().warning("Territory: не удалось прочитать общину #" + id + ": " + e.getMessage());
            return null;
        }
    }

    private TerritoryResult<Void> preflightApplicant(UUID uuid) {
        if (findCommunityForPlayer(uuid) != null) {
            return TerritoryResult.fail("Ты уже состоишь в общине.");
        }
        try {
            if (db.getPendingApplicationForPlayer(uuid) != null) {
                return TerritoryResult.fail("У тебя уже есть заявка на рассмотрении.");
            }
        } catch (SQLException e) {
            return TerritoryResult.fail("Внутренняя ошибка, попробуй ещё раз позже.");
        }
        return TerritoryResult.ok(null);
    }

    private TerritoryResult<Void> validateNameAndTag(String name, String tag) {
        if (name == null || !NAME_PATTERN.matcher(name.trim()).matches()) {
            return TerritoryResult.fail("Название должно быть от 2 до 64 символов (буквы/цифры/пробел/-/_).");
        }
        int tagMin = plugin.config().territoryTagMinLength();
        int tagMax = plugin.config().territoryTagMaxLength();
        if (tag == null || tag.length() < tagMin || tag.length() > tagMax || !TAG_PATTERN.matcher(tag).matches()) {
            return TerritoryResult.fail("Тег должен быть " + tagMin + "-" + tagMax + " латинских букв/цифр, без пробелов и `|`.");
        }
        return TerritoryResult.ok(null);
    }

    private TerritoryResult<Void> validateBounds(int x1, int z1, int x2, int z2) {
        int width = Math.abs(x2 - x1) + 1;
        int depth = Math.abs(z2 - z1) + 1;
        int min = plugin.config().territoryMinSize();
        int max = plugin.config().territoryMaxSize();
        if (width < min || depth < min) {
            return TerritoryResult.fail("Территория слишком маленькая (минимум " + min + "x" + min + ").");
        }
        if (width > max || depth > max) {
            return TerritoryResult.fail("Территория слишком большая (максимум " + max + "x" + max + ").");
        }
        return TerritoryResult.ok(null);
    }

    /** Перемечает зону в TerritoryTitles (границы/lock) и сразу дотягивает closed+exempt - источник правды всегда БД SunShine (см. ТЗ раздел 3.6). */
    private void markZoneAndSyncState(Community community) {
        if (community == null) return;
        String title = "§6§l" + community.name(); // только название, как у города - без клан-тега (тот виден в нике/BlueMap)
        String subtitle = "§7Территория общины";
        plugin.territoryTitlesIntegration().markCommunityZone(community.name(), community.world(),
                community.x1(), community.z1(), community.x2(), community.z2(), title, subtitle, community.isLocked());
        pushMemberSync(community);
        if (plugin.territoryBlueMapIntegration() != null) {
            plugin.territoryBlueMapIntegration().markCommunity(community);
        }
    }

    /** Проталкивает актуальный список участников в TerritoryTitles как исключение из closed - только если территория реально закрыта (иначе не нужно). */
    private void pushMemberSync(Community community) {
        if (!community.closed()) return;
        // Вместе с участниками пускаем и остальные аккаунты тех же Discord - иначе человек, записанный
        // под одним UUID, не смог бы зайти в свою закрытую территорию со второго аккаунта.
        Set<UUID> exempt = new java.util.HashSet<>(members(community.id()));
        for (UUID member : List.copyOf(exempt)) {
            String discordId = plugin.linkManager().discordIdFor(member);
            if (discordId != null && !discordId.isBlank()) exempt.addAll(plugin.linkManager().uuidsForDiscordId(discordId));
        }
        plugin.territoryTitlesIntegration().setExemptMembers(community.name(), exempt);
    }

    /** Лог админ-действий в отдельный Discord-канал (territory.channels.log) - аналог channels.moderation-log в SunShine. Тихо ничего не делает, если канал не настроен. */
    private void logAdminAction(String actionLabel, String communityName, String actorName, String details) {
        String channelId = plugin.config().territoryLogChannelId();
        if (channelId.isBlank()) return;
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(actionLabel + ": " + communityName)
                .setColor(new Color(0xED4245))
                .addField("Кто", actorName == null || actorName.isBlank() ? "неизвестно" : actorName, true)
                .setTimestamp(Instant.now());
        if (details != null && !details.isBlank()) {
            embed.addField("Детали", details, false);
        }
        plugin.discordBot().sendEmbedToChannel(channelId, embed.build());
    }

    private String safeName(UUID uuid) {
        String name = plugin.linkManager().nameFor(uuid);
        return name == null ? uuid.toString() : name;
    }

    /**
     * Мгновенно пересчитывает и переставляет Discord-ник игрока (тег общины включается/выключается)
     * сразу при вступлении/исключении/удалении общины - без этого игрок увидел бы тег в нике только
     * после следующего входа на сервер (см. {@link com.sunshine.plugin.SunShinePlugin#formatDiscordNickname}).
     * Тихо ничего не делает, если синхронизация ников выключена в конфиге или игрок не привязан.
     */
    private void resyncNickname(UUID uuid) {
        if (!plugin.config().syncNicknameOnJoin()) return;
        String discordId = plugin.linkManager().discordIdFor(uuid);
        if (discordId == null || discordId.isBlank()) return;
        String minecraftName = plugin.linkManager().nameFor(uuid);
        if (minecraftName == null) return;
        plugin.discordBot().setNickname(discordId, plugin.formatDiscordNickname(uuid, minecraftName));
    }
}
