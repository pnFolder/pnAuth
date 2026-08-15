package ru.privatenull.pnauth.event;
import java.util.UUID;
import ru.privatenull.pnauth.api.AuthStatus;
public record UserJoinedEvent(UUID uniqueId, String username, String ip, AuthStatus status) implements UserAuthEvent { }
