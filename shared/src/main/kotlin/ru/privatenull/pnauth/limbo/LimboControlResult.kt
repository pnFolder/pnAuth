package ru.privatenull.pnauth.limbo

enum class LimboControlResult(val code: Int) {
    ACCEPTED(0),
    INVALID_ARGUMENT(1),
    PLAYER_NOT_FOUND(2),
    QUEUE_FULL(3),
    QUEUE_CLOSED(4);

    companion object {
        @JvmStatic
        fun fromCode(code: Int): LimboControlResult {
            return values().firstOrNull { it.code == code }
                ?: throw IllegalStateException("Unknown PicoLimbo control result: $code")
        }
    }
}
