package ru.privatenull.pnauth.event;
import java.util.UUID;
public record UserRegisteredEvent(UUID uniqueId, String username, String ip, boolean administrative) implements UserAuthEvent { }
