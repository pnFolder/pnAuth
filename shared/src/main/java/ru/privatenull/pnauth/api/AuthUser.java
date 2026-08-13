package ru.privatenull.pnauth.api;

import java.time.Instant;
import java.util.UUID;

public record AuthUser(
        UUID uniqueId,
        String username,
        Instant registeredAt,
        Instant lastLoginAt,
        boolean premium,
        boolean totpEnabled,
        String lastIp,
        DialogPreference dialogPreference
) {
    public AuthUser(UUID uniqueId, String username, Instant registeredAt, Instant lastLoginAt) {
        this(uniqueId, username, registeredAt, lastLoginAt, false, false, null, DialogPreference.AUTO);
    }
}
