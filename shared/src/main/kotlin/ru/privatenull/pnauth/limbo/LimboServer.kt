package ru.privatenull.pnauth.limbo

import java.util.Optional

interface LimboServer : AutoCloseable {
    fun id(): String

    fun host(): String

    fun port(): Int

    fun state(): LimboServerState

    fun control(): Optional<LimboControl> = Optional.empty()

    fun start()

    fun stop()

    override fun close() {
        stop()
    }
}
