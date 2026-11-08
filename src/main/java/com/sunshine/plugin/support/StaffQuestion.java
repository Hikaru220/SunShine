package com.sunshine.plugin.support;

import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;

import java.util.List;

public record StaffQuestion(String id, String label, TextInputStyle style) {

    // Discord modals allow at most 5 fields - matches exactly.
    public static final List<StaffQuestion> ALL = List.of(
            new StaffQuestion("nickname", "Ваш игровой никнейм", TextInputStyle.SHORT),
            new StaffQuestion("age", "Ваш возраст", TextInputStyle.SHORT),
            new StaffQuestion("skills", "Какими навыками вы обладаете", TextInputStyle.PARAGRAPH),
            new StaffQuestion("motivation", "Почему вы хотите стать частью команды", TextInputStyle.PARAGRAPH),
            new StaffQuestion("rules", "Оцените ваше знание правил сервера", TextInputStyle.SHORT)
    );
}
