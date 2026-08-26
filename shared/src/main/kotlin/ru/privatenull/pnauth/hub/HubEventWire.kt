package ru.privatenull.pnauth.hub

import ru.privatenull.pnauth.cluster.ClusterEvent
import java.time.Instant
import java.util.UUID

class HubEventWire {
    var sequence: Long = 0
    var id: String = ""
    var type: String = ""
    var sourceNode: String = ""
    var playerId: String? = null
    var ticketId: String? = null
    var occurredAt: Long = 0
    var attributes: Map<String, String> = emptyMap()

    fun event(): ClusterEvent = ClusterEvent(
        UUID.fromString(id), ClusterEvent.Type.valueOf(type), sourceNode,
        playerId?.let(UUID::fromString), ticketId, Instant.ofEpochMilli(occurredAt), attributes
    )

    companion object {
        fun from(event: ClusterEvent, sequence: Long = 0): HubEventWire = HubEventWire().apply {
            this.sequence = sequence
            id = event.id.toString()
            type = event.type.name
            sourceNode = event.sourceNode
            playerId = event.playerId?.toString()
            ticketId = event.ticketId
            occurredAt = event.occurredAt.toEpochMilli()
            attributes = event.attributes
        }
    }
}

class HubEventBatch {
    var events: List<HubEventWire> = emptyList()
    var lastSequence: Long = 0
}
