package ru.privatenull.pnauth.storage;

import ru.privatenull.pnauth.api.AuthUser;
import ru.privatenull.pnauth.api.DialogPreference;

import java.time.Instant;
import java.util.UUID;

public record AuthRecord(
        UUID uniqueId,
        String username,
        String realName,
        PasswordHash passwordHash,
        long registeredAt,
        Long lastLoginAt,
        boolean premium,
        String registeredIp,
        String lastIp,
        String totpSecret,
        DialogPreference dialogPreference
) {
    public AuthRecord {
        dialogPreference = dialogPreference == null ? DialogPreference.AUTO : dialogPreference;
    }

    public AuthRecord(UUID uniqueId, String username, PasswordHash passwordHash, long registeredAt, Long lastLoginAt) {
        this(uniqueId, username, username, passwordHash, registeredAt, lastLoginAt, false, null, null, null,
                DialogPreference.AUTO);
    }

    public AuthRecord(
            UUID uniqueId, String username, String realName, PasswordHash passwordHash, long registeredAt,
            Long lastLoginAt, boolean premium, String registeredIp, String lastIp, String totpSecret
    ) {
        this(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium, registeredIp, lastIp,
                totpSecret, DialogPreference.AUTO);
    }

    public AuthUser toApiUser() {
        return new AuthUser(
                uniqueId,
                username,
                Instant.ofEpochMilli(registeredAt),
                lastLoginAt == null ? null : Instant.ofEpochMilli(lastLoginAt),
                premium,
                totpSecret != null && !totpSecret.isBlank(),
                lastIp,
                dialogPreference
        );
    }

    public static AuthRecord registered(
            UUID uniqueId, String username, String realName, PasswordHash passwordHash, long timestamp, String ip
    ) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, timestamp, timestamp,
                false, ip, ip, null, DialogPreference.AUTO);
    }

    public AuthRecord withUsername(String username, String realName) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
                registeredIp, lastIp, totpSecret, dialogPreference);
    }

    public AuthRecord withPasswordHash(PasswordHash passwordHash) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
                registeredIp, lastIp, totpSecret, dialogPreference);
    }

    public AuthRecord withLogin(long timestamp, String ip) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, timestamp, premium,
                registeredIp, ip, totpSecret, dialogPreference);
    }

    public AuthRecord withPremium(boolean premium) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
                registeredIp, lastIp, totpSecret, dialogPreference);
    }

    public AuthRecord withTotpSecret(String totpSecret) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
                registeredIp, lastIp, totpSecret, dialogPreference);
    }

    public AuthRecord withDialogPreference(DialogPreference preference) {
        return new AuthRecord(uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
                registeredIp, lastIp, totpSecret, preference);
    }
}
