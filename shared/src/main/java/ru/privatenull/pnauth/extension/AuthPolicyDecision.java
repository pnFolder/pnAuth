package ru.privatenull.pnauth.extension;
import java.time.Duration;
public record AuthPolicyDecision(Type type, String provider, String message, Duration lifetime) {
    public enum Type { ALLOW, DENY, REQUIRE_VERIFICATION }
    public static AuthPolicyDecision allow() { return new AuthPolicyDecision(Type.ALLOW, "", "", Duration.ZERO); }
    public static AuthPolicyDecision deny(String message) { return new AuthPolicyDecision(Type.DENY, "", message, Duration.ZERO); }
    public static AuthPolicyDecision requireVerification(String provider, String message, Duration lifetime) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        return new AuthPolicyDecision(Type.REQUIRE_VERIFICATION, provider, message,
                lifetime == null || lifetime.isNegative() || lifetime.isZero() ? Duration.ofMinutes(5) : lifetime);
    }
}
