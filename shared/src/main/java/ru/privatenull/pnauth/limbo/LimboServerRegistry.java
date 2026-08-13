package ru.privatenull.pnauth.limbo;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LimboServerRegistry {
    private final Map<String, LimboServerProvider> providers = new LinkedHashMap<>();

    public void register(LimboServerProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("Invalid limbo provider");
        }
        if (providers.putIfAbsent(provider.id().toLowerCase(), provider) != null) {
            throw new IllegalArgumentException("Limbo provider is already registered: " + provider.id());
        }
    }

    public LimboServer create(String providerId, LimboServerContext context) {
        LimboServerProvider provider = providers.get(providerId.toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown limbo provider: " + providerId);
        }
        return provider.create(context);
    }
}
