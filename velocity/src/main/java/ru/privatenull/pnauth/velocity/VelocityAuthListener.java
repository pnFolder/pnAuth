package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ru.privatenull.pnauth.policy.AuthAccessService;
import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.command.CommandRoots;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator;
import ru.privatenull.pnauth.flow.JoinDecision;
import ru.privatenull.pnauth.flow.PlayerConnection;
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator;

public final class VelocityAuthListener {
    private final ProxyServer proxy;
    private final AuthApi auth;
    private final AuthLifecycleCoordinator lifecycle;
    private final MessageFormat messageFormat;
    private final RegisteredServer embeddedAuthServer;
    private final ProxySettings proxySettings;
    private final VelocityDialogCoordinator dialogs;
    private final AuthMessages messages;

    public VelocityAuthListener(
            ProxyServer proxy,
            AuthApi auth,
            AuthLifecycleCoordinator lifecycle,
            MessageFormat messageFormat,
            RegisteredServer embeddedAuthServer,
            ProxySettings proxySettings,
            VelocityDialogCoordinator dialogs,
            AuthMessages messages
    ) {
        this.proxy = proxy;
        this.auth = auth;
        this.lifecycle = lifecycle;
        this.messageFormat = messageFormat;
        this.embeddedAuthServer = embeddedAuthServer;
        this.proxySettings = proxySettings;
        this.dialogs = dialogs;
        this.messages = messages;
    }

    @Subscribe
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        String ip = ip(player.getRemoteAddress());
        return EventTask.resumeWhenComplete(lifecycle.join(new PlayerConnection(
                        player.getUniqueId(), player.getUsername(), ip))
                .handle((decision, error) -> {
                    if (error != null) {
                        event.setInitialServer(null);
                        player.disconnect(VelocityMessages.component(lifecycle.message("access.database"), messageFormat));
                        return null;
                    }
                    if (decision.route() == JoinDecision.Route.BACKEND) {
                        RegisteredServer backend = resolveBackend(player);
                        event.setInitialServer(backend != null ? backend : authServer());
                    } else {
                        RegisteredServer target = authServer();
                        event.setInitialServer(target);
                        if (target == null) {
                            player.disconnect(VelocityMessages.component(
                        lifecycle.authServerMissingMessage(), messageFormat));
                        }
                    }
                    return null;
                }).toCompletableFuture());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse("");
        if (!current.equalsIgnoreCase(lifecycle.authServerName()) || auth.isAuthenticated(player.getUniqueId())) return;
        ru.privatenull.pnauth.api.AuthStatus status = auth.status(player.getUniqueId());
        boolean dialogShown = dialogs != null && dialogs.show(player, status);
        boolean dialogStatus = status == ru.privatenull.pnauth.api.AuthStatus.UNREGISTERED
                || status == ru.privatenull.pnauth.api.AuthStatus.UNAUTHENTICATED;
        int protocol = player.getProtocolVersion().getProtocol();
        boolean platformSupportsDialogs = dialogs != null && dialogs.available();
        if (!dialogShown && (!dialogStatus || auth.shouldUseCommandFallback(
                player.getUniqueId(), protocol, platformSupportsDialogs))) {
            player.sendMessage(VelocityMessages.component(messages.prompt(auth.status(player.getUniqueId())), messageFormat));
        }
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        String ip = ip(event.getConnection().getRemoteAddress());
        int online = (int) proxy.getAllPlayers().stream()
                .filter(player -> ip(player.getRemoteAddress()).equals(ip))
                .count();
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        // The admission service owns the rule; this adapter only maps its result to Velocity's event result.
        lifecycle.admit(event.getUsername(), ip, online).whenComplete((decision, error) -> {
            if (error != null) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        VelocityMessages.component(lifecycle.blockedMessage(), messageFormat)));
            } else if (!decision.allowed()) {
                String key = switch (decision.reason()) {
                    case BANNED -> "access.banned";
                    case ONLINE_IP_LIMIT -> "access.ip_online_limit";
                    case POLICY_DENIED -> "access.blocked";
                    default -> "access.ip_registered_limit";
                };
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        VelocityMessages.component(lifecycle.message(key), messageFormat)));
            } else if (decision.forceOnlineMode()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            } else {
                // The proxy may globally run in online mode. Non-premium accounts must
                // explicitly bypass Mojang authentication instead of inheriting that mode.
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            }
            result.complete(null);
        });
        return EventTask.resumeWhenComplete(result);
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (CommandRoots.isExactRoot(event.getCommand(), "_pnauthui")) return;
        if (CommandRoots.isPasswordAuthenticationCommand(event.getCommand()) && dialogs != null
                && !dialogs.allowAuthenticationCommand(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            dialogs.requestCaptcha(player);
            return;
        }
        if (lifecycle.command(player.getUniqueId(), event.getCommand()) == AuthAccessService.AccessDecision.DENY) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(VelocityMessages.component(lifecycle.blockedMessage(), messageFormat));
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (lifecycle.server(event.getPlayer().getUniqueId(), event.getOriginalServer().getServerInfo().getName())
                == AuthAccessService.ServerAccessDecision.ALLOW) {
            return;
        }
        RegisteredServer authServer = authServer();
        if (authServer == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(VelocityMessages.component(lifecycle.authServerMissingMessage(), messageFormat));
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(authServer));
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!event.getServer().getServerInfo().getName().equalsIgnoreCase(lifecycle.authServerName())) return;
        if (auth.isAuthenticated(event.getPlayer().getUniqueId())) {
            RegisteredServer backend = resolveBackend(event.getPlayer());
            if (backend != null) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(backend));
                return;
            }
        }
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                event.getServerKickReason().orElse(
                        VelocityMessages.component(lifecycle.authServerMissingMessage(), messageFormat))));
    }

    private RegisteredServer authServer() {
        return embeddedAuthServer != null ? embeddedAuthServer
                : proxy.getServer(lifecycle.authServerName()).orElse(null);
    }

    private RegisteredServer resolveBackend(Player player) {
        if (!proxySettings.hasBackendServer() && proxySettings.forcedHosts().isEmpty()) return null;
        String name = player.getVirtualHost()
                .map(host -> proxySettings.forcedHosts().getOrDefault(
                        host.getHostString().toLowerCase(java.util.Locale.ROOT), proxySettings.backendServer()))
                .orElse(proxySettings.backendServer());
        return name == null || name.isBlank() ? null : proxy.getServer(name).orElse(null);
    }

    private static String ip(java.net.InetSocketAddress address) {
        if (address == null) return "unknown";
        java.net.InetAddress resolved = address.getAddress();
        return resolved == null ? address.getHostString() : resolved.getHostAddress();
    }

}
