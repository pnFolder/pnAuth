package ru.privatenull.pnauth.event;
import java.util.UUID;
public record PasswordChangedEvent(UUID uniqueId, String username, boolean administrative) implements UserAuthEvent { }
