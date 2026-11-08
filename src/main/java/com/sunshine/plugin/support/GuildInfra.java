package com.sunshine.plugin.support;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds (or auto-creates) the category and roles used by a support feature on a guild,
 * caching the result for the lifetime of the plugin so repeat lookups are free.
 */
public class GuildInfra {

    public record Resolved(Category category, List<Role> roles) {}

    private final Map<String, Resolved> cache = new ConcurrentHashMap<>();

    public Resolved resolve(Guild guild, String cacheKey, String categoryName, String categoryIdOverride,
                             String roleName, List<String> roleIdsOverride) {
        String key = guild.getId() + ":" + cacheKey;
        Resolved cached = cache.get(key);
        if (cached != null) return cached;

        Category category = resolveCategory(guild, categoryName, categoryIdOverride);
        List<Role> roles = resolveRoles(guild, roleName, roleIdsOverride);

        Resolved resolved = new Resolved(category, roles);
        cache.put(key, resolved);
        return resolved;
    }

    private Category resolveCategory(Guild guild, String categoryName, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            Category byId = guild.getCategoryById(overrideId.trim());
            if (byId != null) return byId;
        }
        List<Category> byName = guild.getCategoriesByName(categoryName, true);
        if (!byName.isEmpty()) return byName.get(0);
        return guild.createCategory(categoryName).complete();
    }

    private List<Role> resolveRoles(Guild guild, String roleName, List<String> overrideIds) {
        if (overrideIds != null && !overrideIds.isEmpty()) {
            List<Role> resolved = new ArrayList<>();
            for (String id : overrideIds) {
                if (id == null || id.isBlank()) continue;
                Role role = guild.getRoleById(id.trim());
                if (role != null) resolved.add(role);
            }
            if (!resolved.isEmpty()) return resolved;
        }

        List<Role> byName = guild.getRolesByName(roleName, true);
        if (!byName.isEmpty()) return List.of(byName.get(0));

        Role created = guild.createRole()
                .setName(roleName)
                .setColor(new Color(0xff, 0xcb, 0x6b))
                .setMentionable(true)
                .reason("Автосоздание роли " + roleName + " для Sunshine_Support")
                .complete();
        return List.of(created);
    }
}
