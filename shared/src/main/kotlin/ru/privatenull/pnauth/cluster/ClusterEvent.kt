package ru.privatenull.pnauth.cluster

import java.time.Instant
import java.util.UUID

data class ClusterEvent(
    val id: UUID,
    val type: Type,
    val sourceNode: String,
    val playerId: UUID?,
    val ticketId: String?,
    val occurredAt: Instant,
    val attributes: Map<String, String> = emptyMap()
) {
    enum class Type {
        SESSION_AUTHENTICATED,
        SESSION_INVALIDATED,
        ACCOUNT_CHANGED,
        ACCOUNT_DELETED,
        VERIFICATION_REQUESTED,
        VERIFICATION_RESOLVED,
        NODE_HEARTBEAT
    }
}
