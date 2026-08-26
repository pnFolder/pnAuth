package ru.privatenull.pnauth.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.config.ProcessingTitleSettings

class ProcessingTitleAnimationTest {
    @Test
    fun rendersConfiguredFramesAndLocalizedTextPlaceholder() {
        val settings = ProcessingTitleSettings.Animation(
            ProcessingTitleSettings.Type.FRAMES,
            emptyList(),
            12,
            listOf("<red>{text}.</red>", "<gold>{text}..</gold>")
        )

        assertEquals(
            listOf("<red>ПРОВЕРКА.</red>", "<gold>ПРОВЕРКА..</gold>"),
            ProcessingTitleAnimation.generateFrames("ПРОВЕРКА", settings)
        )
    }

    @Test
    fun noneProducesSinglePlainFrame() {
        val settings = ProcessingTitleSettings.Animation(
            ProcessingTitleSettings.Type.NONE,
            emptyList(),
            1,
            emptyList()
        )
        assertEquals(listOf("Проверка"), ProcessingTitleAnimation.generateFrames("Проверка", settings))
    }

    @Test
    fun gradientProducesEveryConfiguredPhaseInsteadOfRotatingDuplicateColors() {
        val settings = ProcessingTitleSettings.Animation.defaults()
        val frames = ProcessingTitleAnimation.generateFrames("ПРОВЕРКА", settings)

        assertEquals(48, frames.size)
        assertEquals(48, frames.toSet().size)
        assertEquals(true, frames.first().contains(":-1.0000>"))
    }
}
