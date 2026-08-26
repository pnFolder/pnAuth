package ru.privatenull.pnauth.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class DefaultAuthExtensionRegistryTest {
    @Test
    fun verificationTicketAllowsExactlyOneRetry() {
        val registry = DefaultAuthExtensionRegistry()
        val calls = AtomicInteger()
        val states = ArrayList<VerificationTicket.Status>()
        registry.onTicket { ticket -> states.add(ticket.status) }
        registry.register("discord", 100) {
            calls.incrementAndGet()
            CompletableFuture.completedFuture(
                AuthPolicyDecision.requireVerification(
                    "discord", "Approve the login in Discord", Duration.ofMinutes(2)
                )
            )
        }
        val player = UUID.randomUUID()
        val context = AuthOperationContext.user(AuthOperation.LOGIN, player, "Alex", "127.0.0.1")

        assertEquals(
            AuthPolicyDecision.Type.REQUIRE_VERIFICATION,
            registry.evaluate(context).toCompletableFuture().join().type
        )
        val ticket = registry.pending(player).orElseThrow()
        assertEquals("discord", ticket.provider)
        assertTrue(registry.approve(ticket.id))
        assertEquals(AuthPolicyDecision.Type.ALLOW, registry.evaluate(context).toCompletableFuture().join().type)
        assertEquals(
            AuthPolicyDecision.Type.REQUIRE_VERIFICATION,
            registry.evaluate(context).toCompletableFuture().join().type
        )
        assertEquals(2, calls.get())
        assertEquals(
            listOf(
                VerificationTicket.Status.PENDING,
                VerificationTicket.Status.APPROVED,
                VerificationTicket.Status.PENDING
            ), states
        )
    }

    @Test
    fun higherPriorityHookCanDenyBeforeOtherExtensions() {
        val registry = DefaultAuthExtensionRegistry()
        val lower = AtomicInteger()
        registry.register("lower", 1) {
            lower.incrementAndGet()
            CompletableFuture.completedFuture(AuthPolicyDecision.allow())
        }
        registry.register("network-policy", 100) {
            CompletableFuture.completedFuture(AuthPolicyDecision.deny("blocked network"))
        }

        val decision = registry.evaluate(
            AuthOperationContext.user(
                AuthOperation.LOGIN, UUID.randomUUID(), "Alex", "127.0.0.1"
            )
        ).toCompletableFuture().join()

        assertEquals(AuthPolicyDecision.Type.DENY, decision.type)
        assertEquals(0, lower.get())
    }
}
