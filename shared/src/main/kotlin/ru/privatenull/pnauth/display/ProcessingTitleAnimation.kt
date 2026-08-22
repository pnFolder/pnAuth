package ru.privatenull.pnauth.display

import ru.privatenull.pnauth.config.ProcessingTitleSettings
import java.util.Locale

/**
 * Generates animated MiniMessage gradient title frames for smooth visual feedback during password verification.
 */
object ProcessingTitleAnimation {

    @JvmStatic
    fun generateFrames(text: String, settings: ProcessingTitleSettings.Animation): List<String> {
        if (settings.type == ProcessingTitleSettings.Type.NONE) return listOf(text)
        if (settings.type == ProcessingTitleSettings.Type.FRAMES) {
            return settings.frames.map { frame -> frame.replace("{text}", text) }
        }
        val colors = if (settings.colors.size >= 2) settings.colors else listOf("#ff4ecd", "#8b5cf6", "#38bdf8")
        val count = Math.max(1, settings.frameCount)
        val frames = ArrayList<String>(count)
        val palette = java.lang.String.join(":", colors)
        for (i in 0 until count) {
            val phase = -1.0 + (2.0 * i / count)
            frames += "<gradient:$palette:${String.format(Locale.ROOT, "%.4f", phase)}><bold>$text</bold></gradient>"
        }
        return frames
    }
}
