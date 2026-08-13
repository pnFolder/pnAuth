package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import ru.privatenull.pnauth.command.AuthPlatformBridge;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;

import java.net.InetSocketAddress;
import java.util.UUID;

final class BungeeAuthActions implements AuthPlatformBridge {
    private final ProxyServer proxy;
    private final ProxySettings settings;
    private final AuthMessages messages;

    BungeeAuthActions(ProxyServer proxy, ProxySettings settings, AuthMessages messages) {
        this.proxy = proxy;
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public void authenticated(UUID uniqueId) {
        ProxiedPlayer player = proxy.getPlayer(uniqueId);
        if (player == null) return;
        ServerInfo target = target(player);
        if (target == null || target.equals(player.getServer() == null ? null : player.getServer().getInfo())) return;
        player.connect(target, (success, error) -> {
            if (!Boolean.TRUE.equals(success) && player.isConnected()) {
                player.disconnect(BungeeMessages.component(messages.text("access.backend_missing"), messages.format()));
            }
        });
    }

    @Override
    public void loggedOut(UUID uniqueId) {
        disconnect(uniqueId, "logout.disconnect");
    }

    @Override
    public void accountDeleted(UUID uniqueId) {
        disconnect(uniqueId, "unregister.disconnect");
    }

    private void disconnect(UUID uniqueId, String key) {
        ProxiedPlayer player = proxy.getPlayer(uniqueId);
        if (player != null) player.disconnect(BungeeMessages.component(messages.text(key), messages.format()));
    }

    private ServerInfo target(ProxiedPlayer player) {
        String name = settings.backendServer();
        InetSocketAddress host = player.getPendingConnection().getVirtualHost();
        if (host != null) {
            name = settings.forcedHosts().getOrDefault(host.getHostString().toLowerCase(), name);
        }
        ServerInfo configured = proxy.getServerInfo(name);
        if (configured != null) return configured;
        return proxy.getServers().values().stream()
                .filter(server -> !server.getName().equalsIgnoreCase(settings.authServer()))
                .findFirst()
                .orElse(null);
    }
}
