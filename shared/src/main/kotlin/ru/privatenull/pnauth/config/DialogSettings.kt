package ru.privatenull.pnauth.config

@JvmRecord
data class DialogSettings(
    val enabled: Boolean,
    val fallbackToCommands: Boolean,
    val allowPlayerPreference: Boolean,
    val minClientProtocol: Int
) {
    init {
        require(minClientProtocol >= 0) { "minClientProtocol must not be negative" }
    }

    companion object {
        @JvmStatic
        fun defaults(): DialogSettings {
            return DialogSettings(true, true, true, 771)
        }
    }
}
