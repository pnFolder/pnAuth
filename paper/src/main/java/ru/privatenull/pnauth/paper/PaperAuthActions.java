package ru.privatenull.pnauth.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ru.privatenull.pnauth.command.AuthPlatformBridge;
import ru.privatenull.pnauth.message.AuthMessages;

import java.util.UUID;

/** Paper/Folia side effects requested by shared authentication events. */
final class PaperAuthActions implements AuthPlatformBridge {
    private final Plugin plugin;
    private final AuthMessages messages;

    PaperAuthActions(Plugin plugin, AuthMessages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override public void authenticated(UUID uniqueId) { }
    @Override public void authenticated(String username) { }
    @Override public void loggedOut(UUID uniqueId) { disconnect(uniqueId, messages.text("logout.disconnect")); }
    @Override public void accountDeleted(UUID uniqueId) { disconnect(uniqueId, messages.text("unregister.disconnect")); }
    @Override public void accountDeleted(String username) {
        var player = Bukkit.getPlayerExact(username);
        if (player != null) player.getScheduler().run(plugin,
                ignored -> player.kick(net.kyori.adventure.text.Component.text(messages.text("unregister.disconnect"))), null);
    }
    @Override public void broadcast(String message) {
        String rendered = messages.text("broadcast.message", java.util.Map.of("message", message));
        Bukkit.getOnlinePlayers().forEach(player -> player.getScheduler().run(
                plugin, ignored -> player.sendMessage(rendered), null));
    }

    private void disconnect(UUID uniqueId, String reason) {
        var player = Bukkit.getPlayer(uniqueId);
        if (player != null) player.getScheduler().run(plugin,
                ignored -> player.kick(net.kyori.adventure.text.Component.text(reason)), null);
    }
}
