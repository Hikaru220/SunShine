package com.sunshine.plugin.support;

import java.util.List;

public record TicketTheme(String id, String label, String emoji) {

    public static final List<TicketTheme> ALL = List.of(
            new TicketTheme("question", "Вопрос", "❓"),
            new TicketTheme("complaint", "Жалоба", "⚠"),
            new TicketTheme("bug", "Найденный баг", "🐛"),
            new TicketTheme("media", "Заявка на медиа", "🎬"),
            new TicketTheme("other", "Другое", "📝")
    );

    public static TicketTheme byId(String id) {
        for (TicketTheme theme : ALL) {
            if (theme.id.equals(id)) return theme;
        }
        return null;
    }
}
