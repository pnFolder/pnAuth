package ru.privatenull.pnauth.kernel.event

internal object EventDispatchScope {
    private val CURRENT = ThreadLocal<ListenerOptions>()

    @JvmStatic
    fun current(): ListenerOptions? = CURRENT.get()

    @JvmStatic
    fun run(options: ListenerOptions, action: Runnable) {
        val previous = CURRENT.get()
        CURRENT.set(options)
        try {
            action.run()
        } finally {
            if (previous == null) CURRENT.remove() else CURRENT.set(previous)
        }
    }
}
