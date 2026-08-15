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
        backendServer = backendServer == null ? "" : backendServer.trim();
        if (!backendServer.isEmpty() && authServer.equalsIgnoreCase(backendServer)) {
            throw new IllegalArgumentException("authServer and backendServer must differ");
        }
        forcedHosts = forcedHosts == null ? Map.of() : Map.copyOf(forcedHosts);
        if (forcedHosts.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("forcedHosts must not contain blank hostnames or server names");
        }
    }

    public boolean hasBackendServer() {
        return !backendServer.isBlank();
    }

    public static ProxySettings defaults() {
        return new ProxySettings(true, "auth", "hub", Map.of());
    }

    public ProxySettings requiringServerAuth() {
        return requireServerAuth ? this : new ProxySettings(true, authServer, backendServer, forcedHosts);
    }
}
