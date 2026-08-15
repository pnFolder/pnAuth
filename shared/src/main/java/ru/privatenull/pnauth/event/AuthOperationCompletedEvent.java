package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.extension.AuthOperationContext;
public record AuthOperationCompletedEvent(AuthOperationContext context, AuthResult result) implements AuthEvent { }
