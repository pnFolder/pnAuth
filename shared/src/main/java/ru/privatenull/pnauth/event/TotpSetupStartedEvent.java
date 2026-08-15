package ru.privatenull.pnauth.event;
import java.util.UUID;
/** Deliberately excludes the TOTP secret and recovery codes. */
public record TotpSetupStartedEvent(UUID uniqueId, String username) implements UserAuthEvent { }
