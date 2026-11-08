package com.sunshine.plugin.discord;

import com.sunshine.plugin.SunShinePlugin;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class LoginConfirmationInteractionListener extends ListenerAdapter {

    private static final String PREFIX = "sunshine:loginconfirm:";

    private final SunShinePlugin plugin;

    public LoginConfirmationInteractionListener(SunShinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;

        String rest = id.substring(PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon < 0) return;

        String token = rest.substring(0, lastColon);
        boolean confirmed = rest.substring(lastColon + 1).equals("yes");

        plugin.loginConfirmationManager().resolve(token, confirmed);

        event.editMessage(confirmed ? "✅ Вход подтверждён." : "❌ Вход отклонён.")
                .setComponents()
                .queue(null, throwable -> { });
    }
}
