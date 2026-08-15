package ru.privatenull.pnauth.command;

import ru.privatenull.pnauth.event.AuthEventBus;
import ru.privatenull.pnauth.event.AuthSubscription;
import ru.privatenull.pnauth.event.BroadcastRequestedEvent;
import ru.privatenull.pnauth.event.UserAuthenticatedEvent;
import ru.privatenull.pnauth.event.UserLoggedOutEvent;
import ru.privatenull.pnauth.event.UserUnregisteredEvent;

import java.util.List;

/** Converts shared domain events into the small set of unavoidable proxy side effects. */
public final class AuthPlatformEventAdapter implements AutoCloseable {
    private final List<AuthSubscription> subscriptions;

    public AuthPlatformEventAdapter(AuthEventBus events, AuthPlatformBridge platform) {
        subscriptions = List.of(
                events.subscribe(UserAuthenticatedEvent.class, event -> platform.authenticated(event.uniqueId())),
                events.subscribe(UserLoggedOutEvent.class, event -> platform.loggedOut(event.uniqueId())),
                events.subscribe(UserUnregisteredEvent.class, event -> platform.accountDeleted(event.uniqueId())),
                events.subscribe(BroadcastRequestedEvent.class, event -> platform.broadcast(event.message()))
        );
    }

    @Override
    public void close() {
        subscriptions.forEach(AuthSubscription::close);
    }
}
