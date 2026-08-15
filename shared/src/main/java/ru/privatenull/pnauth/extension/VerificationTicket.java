package ru.privatenull.pnauth.extension;
import java.time.Instant;
import java.util.UUID;
public record VerificationTicket(
        String id, String provider, UUID uniqueId, String username, AuthOperation operation,
        String message, Instant expiresAt, Status status
) {
    public enum Status { PENDING, APPROVED, DENIED, EXPIRED }
}
