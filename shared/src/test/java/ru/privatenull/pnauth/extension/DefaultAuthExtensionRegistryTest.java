package ru.privatenull.pnauth.extension;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DefaultAuthExtensionRegistryTest {
    @Test
    void verificationTicketAllowsExactlyOneRetry() {
        DefaultAuthExtensionRegistry registry = new DefaultAuthExtensionRegistry();
        AtomicInteger calls = new AtomicInteger();
        java.util.ArrayList<VerificationTicket.Status> states = new java.util.ArrayList<>();
        registry.onTicket(ticket -> states.add(ticket.status()));
        registry.register("discord", 100, context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(AuthPolicyDecision.requireVerification(
                    "discord", "Approve the login in Discord", Duration.ofMinutes(2)));
        });
        UUID player = UUID.randomUUID();
        AuthOperationContext context = AuthOperationContext.user(AuthOperation.LOGIN, player, "Alex", "127.0.0.1");

        assertEquals(AuthPolicyDecision.Type.REQUIRE_VERIFICATION,
                registry.evaluate(context).toCompletableFuture().join().type());
        VerificationTicket ticket = registry.pending(player).orElseThrow();
        assertEquals("discord", ticket.provider());
        assertTrue(registry.approve(ticket.id()));
        assertEquals(AuthPolicyDecision.Type.ALLOW, registry.evaluate(context).toCompletableFuture().join().type());
        assertEquals(AuthPolicyDecision.Type.REQUIRE_VERIFICATION,
                registry.evaluate(context).toCompletableFuture().join().type());
        assertEquals(2, calls.get());
        assertEquals(java.util.List.of(VerificationTicket.Status.PENDING,
                VerificationTicket.Status.APPROVED, VerificationTicket.Status.PENDING), states);
    }

    @Test
    void higherPriorityHookCanDenyBeforeOtherExtensions() {
        DefaultAuthExtensionRegistry registry = new DefaultAuthExtensionRegistry();
        AtomicInteger lower = new AtomicInteger();
        registry.register("lower", 1, context -> {
            lower.incrementAndGet();
            return CompletableFuture.completedFuture(AuthPolicyDecision.allow());
        });
        registry.register("network-policy", 100, context ->
                CompletableFuture.completedFuture(AuthPolicyDecision.deny("blocked network")));

        AuthPolicyDecision decision = registry.evaluate(AuthOperationContext.user(
                AuthOperation.LOGIN, UUID.randomUUID(), "Alex", "127.0.0.1")).toCompletableFuture().join();

        assertEquals(AuthPolicyDecision.Type.DENY, decision.type());
        assertEquals(0, lower.get());
    }
}
