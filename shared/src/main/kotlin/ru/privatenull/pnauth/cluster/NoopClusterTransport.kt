package ru.privatenull.pnauth.cluster

import java.util.function.Consumer

object NoopClusterTransport : ClusterTransport {
    override fun publish(event: ClusterEvent) = Unit
    override fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable = AutoCloseable {}
    override fun healthy(): Boolean = true
    override fun close() = Unit
}
