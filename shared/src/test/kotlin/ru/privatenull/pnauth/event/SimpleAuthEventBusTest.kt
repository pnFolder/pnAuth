package ru.privatenull.pnauth.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.extension.AuthOperation
import ru.privatenull.pnauth.extension.AuthOperationContext
import ru.privatenull.pnauth.kernel.event.EventPriority
import ru.privatenull.pnauth.kernel.event.ExtensionEvent
import ru.privatenull.pnauth.kernel.event.ListenerMode
import ru.privatenull.pnauth.kernel.event.ListenerOptions
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SimpleAuthEventBusTest {
    @Test
    fun deliversTypedAndParentEventsAndSupportsUnsubscribe() {
        val bus = SimpleAuthEventBus()
        val typed = AtomicInteger()
        val allUsers = AtomicInteger()
        val subscription: AuthSubscription = bus.subscribe(UserJoinedEvent::class.java) { typed.incrementAndGet() }
        bus.subscribe(UserAuthEvent::class.java) { allUsers.incrementAndGet() }

        val event = UserJoinedEvent(
            UUID.randomUUID(), "Alex", "127.0.0.1",
            ru.privatenull.pnauth.api.AuthStatus.UNAUTHENTICATED
        )
        bus.publish(event)
        subscription.close()
        subscription.close()
        bus.publish(event)

        assertEquals(1, typed.get())
        assertEquals(2, allUsers.get())
    }

    @Test
    fun isolatesBrokenListeners() {
        val errors = AtomicInteger()
        val delivered = AtomicInteger()
        val bus = SimpleAuthEventBus { _, _ -> errors.incrementAndGet() }
        bus.subscribe(AuthEvent::class.java) { throw IllegalStateException("broken extension") }
        bus.subscribe(AuthEvent::class.java) { delivered.incrementAndGet() }

        bus.publish(BroadcastRequestedEvent("hello"))

        assertEquals(1, errors.get())
        assertEquals(1, delivered.get())
    }

    @Test
    fun supportsGenericExtensionEventsOutsideAuthentication() {
        val bus = SimpleAuthEventBus()
        val delivered = AtomicInteger()
        bus.subscribe(
            ExampleEconomyEvent::class.java, ListenerOptions.normal("economy-addon")
        ) { event -> delivered.addAndGet(event.amount) }

        bus.publish(ExampleEconomyEvent(7))

        assertEquals(7, delivered.get())
    }

    @Test
    fun higherPriorityMayExplicitlyOverrideCancellationAndAuditWhoDidIt() {
        val bus = SimpleAuthEventBus()
        val event = PreAuthOperationEvent(
            AuthOperationContext.user(
                AuthOperation.LOGIN, UUID.randomUUID(), "Alex", "127.0.0.1"
            )
        )
        bus.subscribe(
            PreAuthOperationEvent::class.java,
            ListenerOptions("network-policy", EventPriority.LOW, false, ListenerMode.MUTATING)
        ) { value -> value.cancel("blocked network") }
        bus.subscribe(
            PreAuthOperationEvent::class.java,
            ListenerOptions("trusted-override", EventPriority.HIGH, true, ListenerMode.MUTATING)
        ) { value -> value.allow() }
        bus.subscribe(PreAuthOperationEvent::class.java, ListenerOptions.monitor("audit-log")) { value ->
            assertFalse(value.cancelled())
            assertEquals(2, value.decisionHistory().size)
        }

        bus.publish(event)

        assertFalse(event.cancelled())
        assertEquals("trusted-override", event.effectiveDecision().ownerId)
        assertEquals(EventPriority.HIGH, event.effectiveDecision().priority)
    }

    @Test
    fun customPrioritiesCannotEnterReservedSystemRange() {
        assertThrows(IllegalArgumentException::class.java) { EventPriority.custom("too-high", 5_001) }
        assertThrows(IllegalArgumentException::class.java) {
            ListenerOptions("third-party", EventPriority.SYSTEM, true, ListenerMode.MUTATING)
        }
    }

    private data class ExampleEconomyEvent(val amount: Int) : ExtensionEvent
}
