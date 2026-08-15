package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ru.privatenull.pnauth.command.AuthPlatformBridge;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.message.MessageFormat;

import java.util.UUID;

final class VelocityAuthActions implements AuthPlatformBridge {
    private final ProxyServer proxy;
    private final ProxySettings settings;
    private final AuthMessages messages;
    private final MessageFormat messageFormat;

    VelocityAuthActions(ProxyServer proxy, ProxySettings settings, AuthMessages messages, MessageFormat messageFormat) {
        this.proxy = proxy;
        this.settings = settings;
        this.messages = messages;
        this.messageFormat = messageFormat;
    }

    @Override
    public void authenticated(UUID uniqueId) {
        Player player = proxy.getPlayer(uniqueId).orElse(null);
        authenticated(player);
    }

    @Override
    public void authenticated(String username) {
        authenticated(proxy.getPlayer(username).orElse(null));
    }

    private void authenticated(Player player) {
        if (player == null) return;
        if (!settings.hasBackendServer()) return;
        // Initial routing is applied by PlayerChooseInitialServerEvent from the shared lifecycle decision.
        // Authentication events only move players that are already connected to an auth server.
        if (player.getCurrentServer().isEmpty()) return;
        String serverName = player.getVirtualHost()
                .map(host -> settings.forcedHosts().getOrDefault(host.getHostString().toLowerCase(), settings.backendServer()))
                .orElse(settings.backendServer());
        RegisteredServer target = proxy.getServer(serverName).orElse(null);
        if (target == null) {
            player.disconnect(VelocityMessages.component(messages.text("access.backend_missing"), messageFormat));
            return;
        }
        if (player.getCurrentServer().map(server -> !server.getServer().equals(target)).orElse(true)) {
            player.createConnectionRequest(target).connect();
        }
    }

    @Override
    public void loggedOut(UUID uniqueId) {
        proxy.getPlayer(uniqueId).ifPresent(player -> player.disconnect(
                VelocityMessages.component(messages.text("logout.disconnect"), messageFormat)));
    }

    @Override
    public void accountDeleted(UUID uniqueId) {
        proxy.getPlayer(uniqueId).ifPresent(player -> player.disconnect(
                VelocityMessages.component(messages.text("unregister.disconnect"), messageFormat)));
    }

    @Override
    public void accountDeleted(String username) {
        proxy.getPlayer(username).ifPresent(player -> player.disconnect(
                VelocityMessages.component(messages.text("unregister.disconnect"), messageFormat)));
    }

    @Override
    public void broadcast(String message) {
        proxy.getAllPlayers().forEach(player -> player.sendMessage(
                VelocityMessages.component(messages.text("broadcast.message", java.util.Map.of("message", message)), messageFormat)));
    }
}
