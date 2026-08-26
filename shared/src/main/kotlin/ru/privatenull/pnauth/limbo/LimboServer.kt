package ru.privatenull.pnauth.limbo

interface LimboServer : AutoCloseable {
    fun id(): String

    fun host(): String

    fun port(): Int

    fun state(): LimboServerState

    fun start()

    fun stop()

    override fun close() {
        stop()
    }
}
