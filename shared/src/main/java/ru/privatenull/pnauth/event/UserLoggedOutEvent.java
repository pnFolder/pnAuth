package ru.privatenull.pnauth.event;
import java.util.UUID;
public record UserLoggedOutEvent(UUID uniqueId, String username) implements UserAuthEvent { }
