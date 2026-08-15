package ru.privatenull.pnauth.event;
import java.util.UUID;
public record UserQuitEvent(UUID uniqueId, String username) implements UserAuthEvent { }
