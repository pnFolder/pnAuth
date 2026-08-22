package ru.privatenull.pnauth.display

import ru.privatenull.pnauth.config.ProcessingTitleSettings
import java.util.Collections

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
        val current = ArrayList(colors)
        
        for (i in 0 until count) {
            val gradientHeader = "<gradient:" + java.lang.String.join(":", current) + "><bold>" + text + "</bold></gradient>"
            frames.add(gradientHeader)
            // Shift colors to animate gradient
            if (current.size > 1) {
                Collections.rotate(current, -1)
            }
        }
        return frames
    }
}
