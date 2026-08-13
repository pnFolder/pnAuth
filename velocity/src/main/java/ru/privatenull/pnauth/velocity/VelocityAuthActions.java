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
        if (player == null) return;
        String serverName = player.getVirtualHost()
                .map(host -> settings.forcedHosts().getOrDefault(host.getHostString().toLowerCase(), settings.backendServer()))
                .orElse(settings.backendServer());
        RegisteredServer target = proxy.getServer(serverName).orElseGet(() -> proxy.getAllServers().stream()
                .filter(server -> !server.getServerInfo().getName().equalsIgnoreCase(settings.authServer()))
                .findFirst()
                .orElse(null));
        if (target != null && player.getCurrentServer().map(server -> !server.getServer().equals(target)).orElse(true)) {
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
}
