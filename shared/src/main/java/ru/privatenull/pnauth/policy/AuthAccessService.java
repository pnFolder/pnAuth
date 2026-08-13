package ru.privatenull.pnauth.policy;

import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.config.ProxySettings;

import java.util.Locale;
import java.util.UUID;

public final class AuthAccessService {
    private final AuthApi auth;
    private final ProxySettings proxySettings;
    private final AccessSettings accessSettings;
    private final AuthMessages messages;

    public AuthAccessService(
            AuthApi auth,
            ProxySettings proxySettings,
            AccessSettings accessSettings,
            AuthMessages messages
    ) {
        this.auth = auth;
        this.proxySettings = proxySettings;
        this.accessSettings = accessSettings;
        this.messages = messages;
    }

    public AccessDecision command(UUID uniqueId, String commandLine) {
        if (auth.isAuthenticated(uniqueId) || accessSettings.unauthenticatedCommands().contains(commandName(commandLine))) {
            return AccessDecision.ALLOW;
        }
        return AccessDecision.DENY;
    }

    public AccessDecision chat(UUID uniqueId) {
        return !accessSettings.blockChat() || auth.isAuthenticated(uniqueId)
                ? AccessDecision.ALLOW
                : AccessDecision.DENY;
    }

    public ServerAccessDecision server(UUID uniqueId, String serverName) {
        if (!proxySettings.requireServerAuth()
                || auth.isAuthenticated(uniqueId)
                || serverName.equalsIgnoreCase(proxySettings.authServer())) {
            return ServerAccessDecision.ALLOW;
        }
        return ServerAccessDecision.REDIRECT_TO_AUTH;
    }

    public String blockedMessage() {
        return messages.text("access.blocked");
    }

    public String message(String key) {
        return messages.text(key);
    }

    public String authServerMissingMessage() {
        return messages.text("access.auth_server_missing", java.util.Map.of("server", proxySettings.authServer()));
    }

    public String authServerName() {
        return proxySettings.authServer();
    }

    private static String commandName(String commandLine) {
        String command = commandLine == null ? "" : commandLine.trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int separator = command.indexOf(' ');
        return separator < 0 ? command : command.substring(0, separator);
    }

    public enum AccessDecision {
        ALLOW,
        DENY
    }

    public enum ServerAccessDecision {
        ALLOW,
        REDIRECT_TO_AUTH
    }
}
