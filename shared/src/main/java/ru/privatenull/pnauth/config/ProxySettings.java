package ru.privatenull.pnauth.config;

import java.util.Map;

public record ProxySettings(boolean requireServerAuth, String authServer, String backendServer, Map<String, String> forcedHosts) {
    public ProxySettings(boolean requireServerAuth, String authServer) {
        this(requireServerAuth, authServer, "hub", Map.of());
    }

    public ProxySettings {
        if (authServer == null || authServer.isBlank()) {
            throw new IllegalArgumentException("authServer must not be blank");
        }
        forcedHosts = forcedHosts == null ? Map.of() : Map.copyOf(forcedHosts);
    }

    public static ProxySettings defaults() {
        return new ProxySettings(false, "auth", "hub", Map.of());
    }

    public ProxySettings requiringServerAuth() {
        return requireServerAuth ? this : new ProxySettings(true, authServer, backendServer, forcedHosts);
    }
}
