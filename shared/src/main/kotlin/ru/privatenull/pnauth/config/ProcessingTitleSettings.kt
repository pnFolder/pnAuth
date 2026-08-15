package ru.privatenull.pnauth.config

import java.time.Duration

/** Visual settings for the title displayed while a password operation is running. */
@JvmRecord
data class ProcessingTitleSettings @JvmOverloads constructor(
    val enabled: Boolean,
    val animation: Animation = Animation.defaults(),
    val timings: Timings = Timings.defaults()
) {
    @JvmRecord
    data class Animation @JvmOverloads constructor(
        val type: Type = Type.GRADIENT,
        val colors: List<String> = emptyList(),
        val frameCount: Int = 12
    ) {
        init {
            if (type == Type.GRADIENT && colors.size < 2) throw IllegalArgumentException("A gradient requires at least two colors")
            for (color in colors) {
                if (!color.matches(Regex("#[0-9a-fA-F]{6}"))) throw IllegalArgumentException("Invalid gradient color: $color")
            }
            if (frameCount !in 1..120) throw IllegalArgumentException("frameCount must be between 1 and 120")
        }

        companion object {
            @JvmStatic
            fun defaults(): Animation = Animation(Type.GRADIENT, listOf("#ff4ecd", "#8b5cf6", "#38bdf8"), 12)
        }
    }

    @JvmRecord
    data class Timings(
        val frameInterval: Duration,
        val minimumDisplay: Duration,
        val resultFadeIn: Duration,
        val resultDisplay: Duration,
        val resultFadeOut: Duration,
        val fadeIn: Duration,
        val stay: Duration,
        val fadeOut: Duration
    ) {
        init {
            positive(frameInterval, "frameInterval")
            nonNegative(minimumDisplay, "minimumDisplay")
            nonNegative(resultFadeIn, "resultFadeIn")
            nonNegative(resultDisplay, "resultDisplay")
            nonNegative(resultFadeOut, "resultFadeOut")
            nonNegative(fadeIn, "fadeIn")
            positive(stay, "stay")
            nonNegative(fadeOut, "fadeOut")
        }

        companion object {
            @JvmStatic
            fun defaults(): Timings = Timings(
                Duration.ofMillis(120), Duration.ofMillis(2500),
                Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(500), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ZERO
            )

            private fun positive(value: Duration, name: String): Duration {
                if (value.isZero || value.isNegative) throw IllegalArgumentException("$name must be positive")
                return value
            }

            private fun nonNegative(value: Duration, name: String): Duration {
                if (value.isNegative) throw IllegalArgumentException("$name must not be negative")
                return value
            }
        }
    }

    enum class Type { NONE, GRADIENT }
}
