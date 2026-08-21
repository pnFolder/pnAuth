package ru.privatenull.pnauth.hub

import ru.privatenull.pnauth.hub.HubEventBatch
import ru.privatenull.pnauth.hub.HubEventWire
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class HubEventBroker(private val capacity: Int = 100_000) {
    private val sequence = AtomicLong()
    private val events = ArrayDeque<HubEventWire>()
    private val monitor = Object()

    fun publish(value: HubEventWire): Long {
        rejectSensitive(value.attributes)
        synchronized(monitor) {
            val next = sequence.incrementAndGet()
            val stored = value.event().let { HubEventWire.from(it, next) }
            events.addLast(stored)
            while (events.size > capacity) events.removeFirst()
            monitor.notifyAll()
            return next
        }
    }

    fun poll(after: Long, waitMillis: Long = 20_000): HubEventBatch {
        synchronized(monitor) {
            if (events.none { it.sequence > after }) monitor.wait(waitMillis.coerceIn(0, 25_000))
            val selected = events.asSequence().filter { it.sequence > after }.take(500).toList()
            return HubEventBatch().apply {
                this.events = selected
                lastSequence = selected.lastOrNull()?.sequence ?: maxOf(after, sequence.get())
            }
        }
    }

    private fun rejectSensitive(attributes: Map<String, String>) {
        require(attributes.keys.none { key -> SENSITIVE.any { key.contains(it, true) } }) {
            "Sensitive attributes are forbidden in Hub events"
        }
    }

    private companion object {
        val SENSITIVE = listOf("password", "secret", "token", "recovery", "credential", "hash")
    }
}
