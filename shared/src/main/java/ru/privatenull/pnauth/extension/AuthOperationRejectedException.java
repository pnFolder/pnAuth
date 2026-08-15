package ru.privatenull.pnauth.extension;
import ru.privatenull.pnauth.api.AuthResult;
public final class AuthOperationRejectedException extends RuntimeException {
    private final AuthResult result;
    public AuthOperationRejectedException(AuthResult result, String message) { super(message); this.result = result; }
    public AuthResult result() { return result; }
}
