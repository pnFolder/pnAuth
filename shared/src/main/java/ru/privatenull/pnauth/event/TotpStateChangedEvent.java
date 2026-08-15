package ru.privatenull.pnauth.event;
import java.util.UUID;
public record TotpStateChangedEvent(UUID uniqueId, String username, boolean enabled) implements UserAuthEvent { }
