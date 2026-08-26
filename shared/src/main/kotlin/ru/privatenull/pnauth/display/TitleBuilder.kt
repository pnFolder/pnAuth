package ru.privatenull.pnauth.display

import ru.privatenull.pnauth.config.ProcessingTitleSettings
import java.time.Duration

/**
 * Platform-agnostic builder for creating and rendering titles with MiniMessage support,
 * custom timing parameters, and animated gradient frames.
 */
class TitleBuilder private constructor(
    val title: String,
    val subtitle: String,
    val fadeIn: Duration,
    val stay: Duration,
    val fadeOut: Duration,
    val animationFrames: List<String>
) {

    companion object {
        @JvmStatic
        fun create(): Builder = Builder()

        @JvmStatic
        fun of(title: String, subtitle: String = ""): TitleBuilder {
            return Builder().title(title).subtitle(subtitle).build()
        }
    }

    class Builder {
        private var titleText: String = ""
        private var subtitleText: String = ""
        private var fadeInDuration: Duration = Duration.ofMillis(200)
        private var stayDuration: Duration = Duration.ofMillis(2000)
        private var fadeOutDuration: Duration = Duration.ofMillis(500)
        private var frames: MutableList<String> = mutableListOf()

        fun title(text: String): Builder {
            this.titleText = text
            return this
        }

        fun subtitle(text: String): Builder {
            this.subtitleText = text
            return this
        }

        fun fadeIn(duration: Duration): Builder {
            this.fadeInDuration = duration
            return this
        }

        fun stay(duration: Duration): Builder {
            this.stayDuration = duration
            return this
        }

        fun fadeOut(duration: Duration): Builder {
            this.fadeOutDuration = duration
            return this
        }

        fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration): Builder {
            this.fadeInDuration = fadeIn
            this.stayDuration = stay
            this.fadeOutDuration = fadeOut
            return this
        }

        fun animateGradient(colors: List<String> = listOf("#ff4ecd", "#8b5cf6", "#38bdf8"), frameCount: Int = 12): Builder {
            val settings = ProcessingTitleSettings.Animation(ProcessingTitleSettings.Type.GRADIENT, colors, frameCount)
            this.frames = ProcessingTitleAnimation.generateFrames(titleText, settings).toMutableList()
            return this
        }

        fun build(): TitleBuilder {
            return TitleBuilder(titleText, subtitleText, fadeInDuration, stayDuration, fadeOutDuration, frames)
        }
    }
}
