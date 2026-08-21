package ru.privatenull.pnauth.cluster

import ru.privatenull.pnauth.hub.HubApiClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class HubClusterTransport(
    private val client: HubApiClient,
    private val nodeId: String
) : ClusterTransport {
    private val listeners = CopyOnWriteArrayList<Consumer<ClusterEvent>>()
    private val running = AtomicBoolean(true)
    private val healthy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pnauth-cluster-hub-$nodeId").apply { isDaemon = true }
    }
    @Volatile private var sequence: Long = 0

    init { executor.execute(::pollLoop) }

    override fun publish(event: ClusterEvent) {
        require(event.sourceNode == nodeId) { "Cluster event source does not match this node" }
        client.publishEvent(event).join()
        healthy.set(true)
    }

    override fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun pollLoop() {
        while (running.get()) {
            try {
                val batch = client.pollEvents(sequence).join()
                sequence = batch.lastSequence
                batch.events.asSequence().map { it.event() }.filter { it.sourceNode != nodeId }.forEach { event ->
                    listeners.forEach { listener -> runCatching { listener.accept(event) } }
                }
                healthy.set(true)
            } catch (_: Exception) {
                healthy.set(false)
                if (running.get()) Thread.sleep(1000)
            }
        }
    }

    override fun healthy(): Boolean = healthy.get()
    override fun close() {
        running.set(false)
        executor.shutdownNow()
        listeners.clear()
    }
}
