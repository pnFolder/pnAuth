package ru.privatenull.pnauth.event;
import java.util.UUID;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.extension.AuthOperation;
public record AuthenticationAttemptEvent(UUID uniqueId, String username, AuthOperation operation, AuthResult result)
        implements UserAuthEvent { }
