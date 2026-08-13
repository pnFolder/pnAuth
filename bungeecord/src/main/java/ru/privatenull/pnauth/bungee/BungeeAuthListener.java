package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.plugin.Listener;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.command.CommandContext;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.command.AuthPlatformBridge;
import ru.privatenull.pnauth.policy.AuthAccessService;
import ru.privatenull.pnauth.message.AuthMessages;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BungeeAuthListener implements Listener {
    private static final Field UNIQUE_ID_FIELD = field("uniqueId");
    private static final Field REWRITE_ID_FIELD = field("rewriteId");
    private final ProxyServer proxy;
    private final Plugin owner;
    private final AuthApi auth;
    private final AuthAccessService access;
    private final AuthMessages messages;
    private final CommandService commands;
    private final AuthPlatformBridge actions;

    public BungeeAuthListener(
            ProxyServer proxy,
            Plugin owner,
            AuthApi auth,
            AuthAccessService access,
            AuthMessages messages,
            CommandService commands,
            AuthPlatformBridge actions
    ) {
        this.proxy = proxy;
        this.owner = owner;
        this.auth = auth;
        this.access = access;
        this.messages = messages;
        this.commands = commands;
        this.actions = actions;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent event) {
        String ip = event.getConnection().getSocketAddress() instanceof java.net.InetSocketAddress address
                ? address.getAddress().getHostAddress() : "unknown";
        int online = (int) proxy.getPlayers().stream()
                .filter(player -> player.getAddress().getAddress().getHostAddress().equals(ip))
                .count();
        event.registerIntent(owner);
        auth.checkAdmission(event.getConnection().getName(), ip, online).whenComplete((decision, error) -> {
            try {
                if (error != null) {
                    event.setCancelled(true);
                    event.setReason(BungeeMessages.component(messages.text("access.database"), messages.format()));
                } else if (!decision.allowed()) {
                    event.setCancelled(true);
                    String key = switch (decision.reason()) {
                        case BANNED -> "access.banned";
                        case ONLINE_IP_LIMIT -> "access.ip_online_limit";
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
    public void onLogin(LoginEvent event) {
        if (!event.getConnection().isOnlineMode()) return;
        try {
            UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + event.getConnection().getName())
                    .getBytes(StandardCharsets.UTF_8));
            if (UNIQUE_ID_FIELD != null) UNIQUE_ID_FIELD.set(event.getConnection(), offlineId);
            if (REWRITE_ID_FIELD != null) REWRITE_ID_FIELD.set(event.getConnection(), offlineId);
        } catch (IllegalAccessException ignored) {
            // Some Bungee forks do not expose the legacy UUID fields.
        }
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        auth.onJoin(player.getUniqueId(), player.getName(), player.getAddress().getAddress().getHostAddress()).thenAccept(status -> {
            if (status == ru.privatenull.pnauth.api.AuthStatus.AUTHENTICATED) {
                actions.authenticated(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        auth.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (access.server(event.getPlayer().getUniqueId(), event.getTarget().getName())
                == AuthAccessService.ServerAccessDecision.ALLOW) {
            return;
        }
        ServerInfo authServer = proxy.getServerInfo(access.authServerName());
        if (authServer == null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(BungeeMessages.components(access.authServerMissingMessage(), messages.format()));
            return;
        }
        event.setTarget(authServer);
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer player)) {
            return;
        }
        if (!event.isCommand()) {
            if (access.chat(player.getUniqueId()) == AuthAccessService.AccessDecision.DENY) {
                event.setCancelled(true);
                sendBlocked(player);
            }
            return;
        }
        if (access.command(player.getUniqueId(), event.getMessage()) == AuthAccessService.AccessDecision.DENY) {
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
        player.sendMessage(BungeeMessages.components(access.blockedMessage(), messages.format()));
    }

    private static Field field(String name) {
        try {
            Field field = Class.forName("net.md_5.bungee.connection.InitialHandler").getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
