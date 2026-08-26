package ru.privatenull.pnauth.kernel.event

@JvmRecord
data class EventPriority(val name: String, val value: Int) : Comparable<EventPriority> {
    init {
        require(name.isNotBlank()) { "priority name is required" }
        require(value in -10_000..10_000) { "priority must be between -10000 and 10000" }
    }

    override fun compareTo(other: EventPriority): Int = value.compareTo(other.value)

    companion object {
        const val MIN_CUSTOM = -5_000
        const val MAX_CUSTOM = 5_000

        @JvmField val LOWEST = EventPriority("LOWEST", -1_000)
        @JvmField val LOW = EventPriority("LOW", -500)
        @JvmField val NORMAL = EventPriority("NORMAL", 0)
        @JvmField val HIGH = EventPriority("HIGH", 500)
        @JvmField val HIGHEST = EventPriority("HIGHEST", 1_000)
        /** Reserved for pnAuth invariants; third-party custom priorities cannot reach it. */
        @JvmField val SYSTEM = EventPriority("SYSTEM", 9_000)
        /** Always last and read-only. */
        @JvmField val MONITOR = EventPriority("MONITOR", 10_000)

        @JvmStatic
        fun custom(name: String, value: Int): EventPriority {
            require(value in MIN_CUSTOM..MAX_CUSTOM) {
                "custom priority must be between $MIN_CUSTOM and $MAX_CUSTOM"
            }
            return EventPriority(name, value)
        }
    }
}
