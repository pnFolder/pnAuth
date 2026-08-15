package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.plugin.Listener;
import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.command.CommandContext;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.command.CommandRoots;
import ru.privatenull.pnauth.policy.AuthAccessService;
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator;
import ru.privatenull.pnauth.flow.PlayerConnection;
import ru.privatenull.pnauth.message.AuthMessages;

import java.util.Arrays;

public final class BungeeAuthListener implements Listener {
    private final ProxyServer proxy;
    private final Plugin owner;
    private final AuthLifecycleCoordinator lifecycle;
    private final AuthMessages messages;
    private final CommandService commands;
    private final BungeeDialogListener dialogs;

    public BungeeAuthListener(
            ProxyServer proxy,
            Plugin owner,
            AuthLifecycleCoordinator lifecycle,
            AuthMessages messages,
            CommandService commands,
            BungeeDialogListener dialogs
    ) {
        this.proxy = proxy;
        this.owner = owner;
        this.lifecycle = lifecycle;
        this.messages = messages;
        this.commands = commands;
        this.dialogs = dialogs;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent event) {
        String ip = event.getConnection().getSocketAddress() instanceof java.net.InetSocketAddress address
                ? ip(address) : "unknown";
        int online = (int) proxy.getPlayers().stream()
                .filter(player -> ip(player.getAddress()).equals(ip))
                .count();
        event.registerIntent(owner);
        lifecycle.admit(event.getConnection().getName(), ip, online).whenComplete((decision, error) -> {
            try {
                if (error != null) {
                    event.setCancelled(true);
                    event.setReason(BungeeMessages.component(messages.text("access.database"), messages.format()));
                } else if (!decision.allowed()) {
                    event.setCancelled(true);
                    String key = switch (decision.reason()) {
                        case BANNED -> "access.banned";
                        case ONLINE_IP_LIMIT -> "access.ip_online_limit";
                        case POLICY_DENIED -> "access.blocked";
                        default -> "access.ip_registered_limit";
                    };
                    event.setReason(BungeeMessages.component(messages.text(key), messages.format()));
                } else {
                    event.getConnection().setOnlineMode(decision.forceOnlineMode());
                }
            } finally {
                event.completeIntent(owner);
            }
        });
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        lifecycle.join(new PlayerConnection(player.getUniqueId(), player.getName(), ip(player.getAddress())));
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        lifecycle.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (lifecycle.server(event.getPlayer().getUniqueId(), event.getTarget().getName())
                == AuthAccessService.ServerAccessDecision.ALLOW) {
            return;
        }
        ServerInfo authServer = proxy.getServerInfo(lifecycle.authServerName());
        if (authServer == null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(BungeeMessages.components(lifecycle.authServerMissingMessage(), messages.format()));
            return;
        }
        event.setTarget(authServer);
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer player)) {
            return;
        }
        if (event.isCommand() && CommandRoots.isExactRoot(event.getMessage(), "_pnauthui")) return;
        if (event.isCommand() && CommandRoots.isPasswordAuthenticationCommand(event.getMessage())
                && !dialogs.allowAuthenticationCommand(player)) {
            event.setCancelled(true);
            dialogs.requestCaptcha(player);
            return;
        }
        if (!event.isCommand()) {
            if (lifecycle.chat(player.getUniqueId()) == AuthAccessService.AccessDecision.DENY) {
                event.setCancelled(true);
                sendBlocked(player);
            }
            return;
        }
        if (lifecycle.command(player.getUniqueId(), event.getMessage()) == AuthAccessService.AccessDecision.DENY) {
            event.setCancelled(true);
            sendBlocked(player);
        }
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        String cursor = event.getCursor().trim();
        String[] parts = cursor.split("\\s+", -1);
        if (parts.length == 0) {
            return;
        }
        String command = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        if (!command.equalsIgnoreCase("auth") && !command.equalsIgnoreCase("pnauth")) {
            return;
        }
        event.getSuggestions().clear();
        event.getSuggestions().addAll(commands.suggest(new CommandContext(
                new BungeeCommandSource((ProxiedPlayer) event.getSender()),
                command,
                Arrays.asList(parts).subList(1, parts.length)
        )));
    }

    private void sendBlocked(ProxiedPlayer player) {
        player.sendMessage(BungeeMessages.components(lifecycle.blockedMessage(), messages.format()));
    }

    private static String ip(java.net.InetSocketAddress address) {
        if (address == null) return "unknown";
        java.net.InetAddress resolved = address.getAddress();
        return resolved == null ? address.getHostString() : resolved.getHostAddress();
    }
}
