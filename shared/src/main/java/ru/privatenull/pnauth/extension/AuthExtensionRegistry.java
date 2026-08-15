package ru.privatenull.pnauth.extension;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
public interface AuthExtensionRegistry {
    AuthExtensionRegistration register(String id, int priority, AuthPolicyHook hook);
    CompletionStage<AuthPolicyDecision> evaluate(AuthOperationContext context);
    Optional<VerificationTicket> pending(UUID uniqueId);
    boolean approve(String ticketId);
    boolean deny(String ticketId);
    AuthExtensionRegistration onTicket(Consumer<VerificationTicket> listener);
}
