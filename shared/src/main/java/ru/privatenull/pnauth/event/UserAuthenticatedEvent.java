package ru.privatenull.pnauth.event;
import java.util.UUID;
public record UserAuthenticatedEvent(UUID uniqueId, String username, Cause cause) implements UserAuthEvent {
    public enum Cause { REGISTER, PASSWORD, TOTP, PREMIUM, SESSION, ADMIN }
}
