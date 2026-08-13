package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ru.privatenull.pnauth.policy.AuthAccessService;
import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.message.MessageFormat;

public final class VelocityAuthListener {
    private final ProxyServer proxy;
    private final AuthApi auth;
    private final AuthAccessService access;
    private final MessageFormat messageFormat;

    public VelocityAuthListener(
            ProxyServer proxy,
            AuthApi auth,
            AuthAccessService access,
            MessageFormat messageFormat
    ) {
        this.proxy = proxy;
        this.auth = auth;
        this.access = access;
        this.messageFormat = messageFormat;
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        int online = (int) proxy.getAllPlayers().stream()
                .filter(player -> player.getRemoteAddress().getAddress().getHostAddress().equals(ip))
                .count();
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        // The admission service owns the rule; this adapter only maps its result to Velocity's event result.
        auth.checkAdmission(event.getUsername(), ip, online).whenComplete((decision, error) -> {
            if (error != null) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        VelocityMessages.component(access.blockedMessage(), messageFormat)));
            } else if (!decision.allowed()) {
                String key = switch (decision.reason()) {
                    case BANNED -> "access.banned";
                    case ONLINE_IP_LIMIT -> "access.ip_online_limit";
                    default -> "access.ip_registered_limit";
                };
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        VelocityMessages.component(access.message(key), messageFormat)));
            } else if (decision.forceOnlineMode()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            } else {
                event.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
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
        if (access.command(player.getUniqueId(), event.getCommand()) == AuthAccessService.AccessDecision.DENY) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(VelocityMessages.component(access.blockedMessage(), messageFormat));
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (access.server(event.getPlayer().getUniqueId(), event.getOriginalServer().getServerInfo().getName())
                == AuthAccessService.ServerAccessDecision.ALLOW) {
            return;
        }
        RegisteredServer authServer = proxy.getServer(access.authServerName()).orElse(null);
        if (authServer == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(VelocityMessages.component(access.authServerMissingMessage(), messageFormat));
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(authServer));
    }

}
