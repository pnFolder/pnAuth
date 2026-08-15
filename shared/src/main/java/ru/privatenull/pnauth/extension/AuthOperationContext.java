package ru.privatenull.pnauth.extension;
import java.util.Map;
import java.util.UUID;
public record AuthOperationContext(
        AuthOperation operation, AuthPhase phase, UUID uniqueId, String username, String ip, Map<String, String> attributes
) {
    public AuthOperationContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
    public AuthOperationContext(AuthOperation operation, UUID uniqueId, String username, String ip, Map<String, String> attributes) {
        this(operation, AuthPhase.BEFORE_EXECUTION, uniqueId, username, ip, attributes);
    }
    public static AuthOperationContext user(AuthOperation operation, UUID id, String username, String ip) {
        return new AuthOperationContext(operation, AuthPhase.BEFORE_EXECUTION, id, username, ip, Map.of());
    }
    public AuthOperationContext at(AuthPhase value) { return new AuthOperationContext(operation, value, uniqueId, username, ip, attributes); }
}
