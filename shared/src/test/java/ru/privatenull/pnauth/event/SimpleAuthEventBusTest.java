package ru.privatenull.pnauth.event;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import ru.privatenull.pnauth.kernel.event.*;

class SimpleAuthEventBusTest {
    @Test
    void deliversTypedAndParentEventsAndSupportsUnsubscribe() {
        SimpleAuthEventBus bus = new SimpleAuthEventBus();
        AtomicInteger typed = new AtomicInteger();
        AtomicInteger allUsers = new AtomicInteger();
        AuthSubscription subscription = bus.subscribe(UserJoinedEvent.class, event -> typed.incrementAndGet());
        bus.subscribe(UserAuthEvent.class, event -> allUsers.incrementAndGet());

        UserJoinedEvent event = new UserJoinedEvent(UUID.randomUUID(), "Alex", "127.0.0.1",
                ru.privatenull.pnauth.api.AuthStatus.UNAUTHENTICATED);
        bus.publish(event);
        subscription.close();
        subscription.close();
        bus.publish(event);

        assertEquals(1, typed.get());
        assertEquals(2, allUsers.get());
    }

    @Test
    void isolatesBrokenListeners() {
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        SimpleAuthEventBus bus = new SimpleAuthEventBus((event, error) -> errors.incrementAndGet());
        bus.subscribe(AuthEvent.class, event -> { throw new IllegalStateException("broken extension"); });
        bus.subscribe(AuthEvent.class, event -> delivered.incrementAndGet());

        bus.publish(new BroadcastRequestedEvent("hello"));

        assertEquals(1, errors.get());
        assertEquals(1, delivered.get());
    }

    @Test
    void supportsGenericExtensionEventsOutsideAuthentication() {
        SimpleAuthEventBus bus = new SimpleAuthEventBus();
        AtomicInteger delivered = new AtomicInteger();
        bus.subscribe(ExampleEconomyEvent.class, ListenerOptions.normal("economy-addon"),
                event -> delivered.addAndGet(event.amount()));

        bus.publish(new ExampleEconomyEvent(7));

        assertEquals(7, delivered.get());
    }

    @Test
    void higherPriorityMayExplicitlyOverrideCancellationAndAuditWhoDidIt() {
        SimpleAuthEventBus bus = new SimpleAuthEventBus();
        PreAuthOperationEvent event = new PreAuthOperationEvent(
                ru.privatenull.pnauth.extension.AuthOperationContext.user(
                        ru.privatenull.pnauth.extension.AuthOperation.LOGIN, UUID.randomUUID(), "Alex", "127.0.0.1"));
        bus.subscribe(PreAuthOperationEvent.class,
                new ListenerOptions("network-policy", EventPriority.LOW, false, ListenerMode.MUTATING),
                value -> value.cancel("blocked network"));
        bus.subscribe(PreAuthOperationEvent.class,
                new ListenerOptions("trusted-override", EventPriority.HIGH, true, ListenerMode.MUTATING),
                value -> value.allow());
        bus.subscribe(PreAuthOperationEvent.class, ListenerOptions.monitor("audit-log"), value -> {
            assertFalse(value.cancelled());
            assertEquals(2, value.decisionHistory().size());
        });

        bus.publish(event);

        assertFalse(event.cancelled());
        assertEquals("trusted-override", event.effectiveDecision().ownerId());
        assertEquals(EventPriority.HIGH, event.effectiveDecision().priority());
    }

    @Test
    void customPrioritiesCannotEnterReservedSystemRange() {
        assertThrows(IllegalArgumentException.class, () -> EventPriority.custom("too-high", 5_001));
        assertThrows(IllegalArgumentException.class, () -> new ListenerOptions(
                "third-party", EventPriority.SYSTEM, true, ListenerMode.MUTATING));
    }

    private record ExampleEconomyEvent(int amount) implements ExtensionEvent { }
}
