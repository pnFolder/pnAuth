package ru.privatenull.pnauth.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ProxySettings(
        boolean requireServerAuth,
        String authServer,
        List<String> authServers,
        String backendServer,
        List<String> backendServers,
        Map<String, String> forcedHosts
) {
    public ProxySettings(boolean requireServerAuth, String authServer) {
        this(requireServerAuth, authServer, List.of(), "hub", List.of(), Map.of());
    }

    public ProxySettings(boolean requireServerAuth, String authServer, String backendServer, Map<String, String> forcedHosts) {
        this(requireServerAuth, authServer, List.of(), backendServer, List.of(), forcedHosts);
    }

    public ProxySettings {
        authServer = normalize(authServer);
        backendServer = normalize(backendServer);
        authServers = normalizeList(authServers);
        backendServers = normalizeList(backendServers);
        forcedHosts = forcedHosts == null ? Map.of() : Map.copyOf(forcedHosts);

        if (requireServerAuth && allAuthServers(authServer, authServers).isEmpty()) {
            throw new IllegalArgumentException(
                    "Authentication routing is enabled, but no authentication server is configured. "
                            + "Set servers.auth-server or servers.auth-servers, or disable servers.require-auth-before-server.");
        }

        Set<String> auth = lowerCaseSet(allAuthServers(authServer, authServers));
        for (String backend : allBackendServers(backendServer, backendServers)) {
            if (auth.contains(backend.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Authentication and backend routes must use different server names. Conflicting server: '"
                                + backend + "'. Check servers.auth-server(s) and servers.backend-server(s).");
            }
        }

        if (forcedHosts.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("servers.forced-hosts must not contain blank hostnames or server names");
        }
    }

    public boolean hasBackendServer() {
        return !backendServerCandidates().isEmpty();
    }

    public List<String> authServerCandidates() {
        return allAuthServers(authServer, authServers);
    }

    public List<String> backendServerCandidates() {
        return allBackendServers(backendServer, backendServers);
    }

    public boolean isAuthServer(String serverName) {
        if (serverName == null || serverName.isBlank()) return false;
        return authServerCandidates().stream().anyMatch(serverName::equalsIgnoreCase);
    }

    public boolean isBackendServer(String serverName) {
        if (serverName == null || serverName.isBlank()) return false;
        return backendServerCandidates().stream().anyMatch(serverName::equalsIgnoreCase);
    }

    public static ProxySettings defaults() {
        return new ProxySettings(true, "auth", List.of(), "hub", List.of(), Map.of());
    }

    public ProxySettings requiringServerAuth() {
        return requireServerAuth ? this
                : new ProxySettings(true, authServer, authServers, backendServer, backendServers, forcedHosts);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static List<String> allAuthServers(String primary, List<String> additional) {
        return merge(primary, additional);
    }

    private static List<String> allBackendServers(String primary, List<String> additional) {
        return merge(primary, additional);
    }

    private static List<String> merge(String primary, List<String> additional) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) result.add(primary.trim());
        if (additional != null) {
            for (String value : additional) {
                if (value != null && !value.isBlank()) result.add(value.trim());
            }
        }
        return List.copyOf(new ArrayList<>(result));
    }

    private static Set<String> lowerCaseSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) result.add(value.toLowerCase(java.util.Locale.ROOT));
        return result;
    }
}
