package ru.privatenull.pnauth.command

import ru.privatenull.pnauth.event.AuthEventBus
import ru.privatenull.pnauth.event.AuthSubscription
import ru.privatenull.pnauth.event.BroadcastRequestedEvent
import ru.privatenull.pnauth.event.UserAuthenticatedEvent
import ru.privatenull.pnauth.event.UserLoggedOutEvent
import ru.privatenull.pnauth.event.UserUnregisteredEvent

/** Converts shared domain events into the small set of unavoidable proxy side effects. */
class AuthPlatformEventAdapter(events: AuthEventBus, platform: AuthPlatformBridge) : AutoCloseable {
    private val subscriptions: List<AuthSubscription> = listOf(
        events.subscribe(UserAuthenticatedEvent::class.java) { event ->
            platform.authenticated(event.uniqueId(), event.cause == UserAuthenticatedEvent.Cause.REGISTER)
        },
        events.subscribe(UserLoggedOutEvent::class.java) { event -> platform.loggedOut(event.uniqueId()) },
        events.subscribe(UserUnregisteredEvent::class.java) { event -> platform.accountDeleted(event.uniqueId()) },
        events.subscribe(BroadcastRequestedEvent::class.java) { event -> platform.broadcast(event.message) }
    )

    override fun close() {
        subscriptions.forEach(AuthSubscription::close)
    }
}
