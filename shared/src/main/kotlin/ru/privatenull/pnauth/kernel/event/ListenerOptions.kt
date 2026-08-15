package ru.privatenull.pnauth.kernel.event

@JvmRecord
data class ListenerOptions @JvmOverloads constructor(
    val ownerId: String,
    val priority: EventPriority = EventPriority.NORMAL,
    val receiveCancelled: Boolean = false,
    val mode: ListenerMode = if (priority == EventPriority.MONITOR) ListenerMode.MONITOR else ListenerMode.MUTATING
) {
    init {
        require(ownerId.isNotBlank()) { "ownerId is required" }
        if (mode == ListenerMode.MUTATING && priority.value > EventPriority.MAX_CUSTOM && ownerId != "pnauth:system") {
            throw IllegalArgumentException("reserved priority")
        }
    }

    companion object {
        @JvmStatic
        fun normal(ownerId: String): ListenerOptions {
            return ListenerOptions(ownerId, EventPriority.NORMAL, false, ListenerMode.MUTATING)
        }

        @JvmStatic
        fun monitor(ownerId: String): ListenerOptions {
            return ListenerOptions(ownerId, EventPriority.MONITOR, true, ListenerMode.MONITOR)
        }
    }
}
