package com.sunshine.plugin.territory;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Автообновляемый список общин в {@code territory.channels.list} (ТЗ раздел 4.3/4.3.1). Редактирует
 * уже опубликованные embed-сообщения ({@code .editMessage()}) вместо постинга новых - ID сообщений
 * персистятся в {@code territory_meta}, чтобы пережить рестарт. Вызывается из
 * {@link CommunityManager#refreshCache()} - то есть на каждое изменение состава/списка общин, не по
 * таймеру (экономия ресурсов - ТЗ, пожелание пользователя).
 */
public final class TerritoryListChannelUpdater {

    private static final int COMMUNITIES_PER_PAGE = 20; // запас от лимита в 25 полей embed

    private final SunShinePlugin plugin;

    public TerritoryListChannelUpdater(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    public void refresh(List<Community> communities) {
        String channelId = plugin.config().territoryListChannelId();
        if (channelId.isBlank() || !plugin.discordBot().isReady()) return;
        TextChannel channel = plugin.discordBot().jda().getTextChannelById(channelId);
        if (channel == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> renderPages(channel, communities));
    }

    private void renderPages(TextChannel channel, List<Community> communities) {
        int pageCount = Math.max(1, (int) Math.ceil(communities.size() / (double) COMMUNITIES_PER_PAGE));
        try {
            int previousPageCount = parseIntOrZero(plugin.territoryDatabase().getMeta("list-page-count"));

            for (int page = 0; page < pageCount; page++) {
                int from = page * COMMUNITIES_PER_PAGE;
                int to = Math.min(from + COMMUNITIES_PER_PAGE, communities.size());
                var embed = buildPageEmbed(communities.subList(from, to), communities.size(), page + 1, pageCount);
                upsertPageMessage(channel, page, embed);
            }

            // Страниц стало меньше (общины удалили) - подчищаем лишние старые сообщения.
            for (int page = pageCount; page < previousPageCount; page++) {
                deletePageMessage(channel, page);
            }

            plugin.territoryDatabase().setMeta("list-page-count", String.valueOf(pageCount));
        } catch (Exception e) {
            plugin.getLogger().warning("Territory: не удалось обновить #список-общин: " + e.getMessage());
        }
    }

    private EmbedBuilder buildPageEmbed(List<Community> pageItems, int totalCount, int pageNumber, int pageCount) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(87, 242, 135))
                .setTitle(pageCount > 1 ? "🏘️ Список общин (стр. " + pageNumber + "/" + pageCount + ")" : "🏘️ Список общин");

        String bannerUrl = plugin.config().territoryListBannerUrl();
        if (pageNumber == 1 && !bannerUrl.isBlank()) {
            embed.setImage(bannerUrl);
        }
        if (pageNumber == 1) {
            embed.setDescription(totalCount == 0
                    ? "📊 Общин пока нет."
                    : "📊 Всего общин: `" + totalCount + "`");
        }

        for (Community community : pageItems) {
            embed.addField(fieldTitle(community), fieldValue(community), false);
        }
        return embed;
    }

    private String fieldTitle(Community community) {
        return "🏘️ " + community.tag() + " — " + community.name();
    }

    private String fieldValue(Community community) {
        List<UUID> members = plugin.communityManager() == null ? List.of() : plugin.communityManager().members(community.id());
        String ownerName = plugin.linkManager().nameFor(community.ownerUuid());
        String memberNames = members.stream()
                .map(u -> plugin.linkManager().nameFor(u))
                .filter(n -> n != null)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("• Владелец: `").append(ownerName == null ? community.ownerUuid() : ownerName).append("`\n");
        sb.append("• Участники (").append(members.size()).append("): `").append(memberNames.isBlank() ? "-" : memberNames).append("`\n");
        sb.append("• Координаты: `").append(community.minX()).append(", ").append(community.minZ())
                .append(" → ").append(community.maxX()).append(", ").append(community.maxZ()).append("`\n");
        sb.append("• Размер: `").append(community.sizeLabel()).append("`\n");
        sb.append("• Доступ: `").append(community.closed() ? "Закрыта" : "Открыта").append("`");
        return sb.toString();
    }

    private void upsertPageMessage(TextChannel channel, int pageIndex, EmbedBuilder embed) throws Exception {
        String metaKey = "list-message-" + pageIndex;
        String existingId = plugin.territoryDatabase().getMeta(metaKey);

        if (existingId != null) {
            channel.editMessageEmbedsById(existingId, embed.build()).queue(
                    null,
                    throwable -> postNewPageMessage(channel, pageIndex, embed) // сообщение удалили вручную - перепостим (ТЗ 4.3)
            );
        } else {
            postNewPageMessage(channel, pageIndex, embed);
        }
    }

    private void postNewPageMessage(TextChannel channel, int pageIndex, EmbedBuilder embed) {
        channel.sendMessageEmbeds(embed.build()).queue(
                message -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.territoryDatabase().setMeta("list-message-" + pageIndex, message.getId());
                    } catch (Exception ignored) {
                        // best-effort - в худшем случае просто перепостим на следующем обновлении
                    }
                }),
                throwable -> { }
        );
    }

    private void deletePageMessage(TextChannel channel, int pageIndex) throws Exception {
        String metaKey = "list-message-" + pageIndex;
        String existingId = plugin.territoryDatabase().getMeta(metaKey);
        if (existingId == null) return;
        channel.deleteMessageById(existingId).queue(null, throwable -> { });
        plugin.territoryDatabase().setMeta(metaKey, null);
    }

    private int parseIntOrZero(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
