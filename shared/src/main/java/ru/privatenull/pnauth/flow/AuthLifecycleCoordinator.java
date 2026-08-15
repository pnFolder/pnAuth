package ru.privatenull.pnauth.flow;

import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.policy.AuthAccessService;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import ru.privatenull.pnauth.event.PreAuthOperationEvent;
import ru.privatenull.pnauth.extension.AuthOperation;
import ru.privatenull.pnauth.extension.AuthOperationContext;

/**
 * Platform-neutral application layer. Proxy listeners translate native events into these methods
 * and only apply the returned decision; authentication policy remains in shared.
 */
public final class AuthLifecycleCoordinator {
    private final AuthApi auth;
    private final AuthAccessService access;

    public AuthLifecycleCoordinator(AuthApi auth, AuthAccessService access) {
        this.auth = auth;
        this.access = access;
    }

    public CompletionStage<AdmissionDecision> admit(String username, String ip, int onlineFromIp) {
        return auth.checkAdmission(username, ip, onlineFromIp);
    }

    public CompletionStage<JoinDecision> join(PlayerConnection player) {
        return auth.onJoin(player.uniqueId(), player.username(), player.ip())
                .thenApply(status -> new JoinDecision(status, auth.isAuthenticated(player.uniqueId())
                        ? JoinDecision.Route.BACKEND : JoinDecision.Route.AUTH_SERVER));
    }

    public void quit(UUID uniqueId) {
        auth.onQuit(uniqueId);
    }

    public AuthAccessService.AccessDecision command(UUID uniqueId, String commandLine) {
        if (cancelled(AuthOperation.COMMAND, uniqueId,
                java.util.Map.of("command", commandRoot(commandLine)))) return AuthAccessService.AccessDecision.DENY;
        return access.command(uniqueId, commandLine);
    }

    public AuthAccessService.AccessDecision chat(UUID uniqueId) {
        if (cancelled(AuthOperation.CHAT, uniqueId, java.util.Map.of())) return AuthAccessService.AccessDecision.DENY;
        return access.chat(uniqueId);
    }

    public AuthAccessService.ServerAccessDecision server(UUID uniqueId, String serverName) {
        if (cancelled(AuthOperation.SERVER_ACCESS, uniqueId,
                java.util.Map.of("server", serverName == null ? "" : serverName))) {
            return AuthAccessService.ServerAccessDecision.REDIRECT_TO_AUTH;
        }
        return access.server(uniqueId, serverName);
    }

    public String authServerName() { return access.authServerName(); }
    public String blockedMessage() { return access.blockedMessage(); }
    public String authServerMissingMessage() { return access.authServerMissingMessage(); }
    public String message(String key) { return access.message(key); }

    private boolean cancelled(AuthOperation operation, UUID uniqueId, java.util.Map<String, String> attributes) {
        var user = auth.user(uniqueId).orElse(null);
        PreAuthOperationEvent event = new PreAuthOperationEvent(new AuthOperationContext(
                operation, uniqueId, user == null ? "" : user.username(),
                user == null ? null : user.lastIp(), attributes));
        auth.events().publish(event);
        return event.cancelled();
    }

    private static String commandRoot(String commandLine) {
        String value = commandLine == null ? "" : commandLine.trim();
        if (value.startsWith("/")) value = value.substring(1);
        int separator = value.indexOf(' ');
        return (separator < 0 ? value : value.substring(0, separator)).toLowerCase(java.util.Locale.ROOT);
    }
}
