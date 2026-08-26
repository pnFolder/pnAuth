package ru.privatenull.pnauth.kernel.event

/** Internal dispatch bridge used by event-bus implementations. */
object EventDispatchRunner {
    @JvmStatic
    fun run(options: ListenerOptions, action: Runnable) {
        EventDispatchScope.run(options, action)
    }
}
