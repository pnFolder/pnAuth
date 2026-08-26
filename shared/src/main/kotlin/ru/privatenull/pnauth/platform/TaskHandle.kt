package ru.privatenull.pnauth.platform

/** A task scheduled through the platform adapter. */
@JvmDefaultWithCompatibility
interface TaskHandle : AutoCloseable {
    fun cancelled(): Boolean
    fun cancel()
    override fun close() {
        cancel()
    }
}
