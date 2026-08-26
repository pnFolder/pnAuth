package ru.privatenull.pnauth.cluster

import ru.privatenull.pnauth.event.AuthSubscription
import ru.privatenull.pnauth.event.PasswordChangedEvent
import ru.privatenull.pnauth.event.UserAuthenticatedEvent
import ru.privatenull.pnauth.event.UserLoggedOutEvent
import ru.privatenull.pnauth.event.UserUnregisteredEvent
import ru.privatenull.pnauth.service.AuthService
import java.time.Instant
import java.nio.file.Path
import java.util.UUID

class AuthClusterCoordinator(
    private val nodeId: String,
    private val auth: AuthService,
    private val transport: ClusterTransport,
    outboxDirectory: Path
) : AutoCloseable {
    private val subscriptions = ArrayList<AuthSubscription>()
    private val clusterSubscription: AutoCloseable
    private val outbox = PersistentClusterOutbox(outboxDirectory, transport)

    init {
        subscriptions += auth.events().subscribe(UserAuthenticatedEvent::class.java) { event ->
            publish(ClusterEvent.Type.SESSION_AUTHENTICATED, event.uniqueId())
        }
        subscriptions += auth.events().subscribe(UserLoggedOutEvent::class.java) { event ->
            publish(ClusterEvent.Type.SESSION_INVALIDATED, event.uniqueId())
        }
        subscriptions += auth.events().subscribe(UserUnregisteredEvent::class.java) { event ->
            auth.invalidateClusterSession(event.uniqueId())
            publish(ClusterEvent.Type.ACCOUNT_DELETED, event.uniqueId())
        }
        subscriptions += auth.events().subscribe(PasswordChangedEvent::class.java) { event ->
            // Смена пароля всегда завершает текущую и все удалённые сессии.
            auth.revokeClusterSession(event.uniqueId())
            publish(ClusterEvent.Type.ACCOUNT_CHANGED, event.uniqueId())
        }
        clusterSubscription = transport.subscribe { event ->
            val playerId = event.playerId ?: return@subscribe
            when (event.type) {
                ClusterEvent.Type.SESSION_INVALIDATED,
                ClusterEvent.Type.ACCOUNT_DELETED -> auth.invalidateClusterSession(playerId)
                ClusterEvent.Type.ACCOUNT_CHANGED -> auth.revokeClusterSession(playerId)
                else -> Unit
            }
        }
    }

    private fun publish(type: ClusterEvent.Type, playerId: UUID) {
        outbox.enqueue(ClusterEvent(UUID.randomUUID(), type, nodeId, playerId, null, Instant.now()))
    }

    override fun close() {
        clusterSubscription.close()
        subscriptions.asReversed().forEach { it.close() }
        subscriptions.clear()
        outbox.close()
        transport.close()
    }
}
