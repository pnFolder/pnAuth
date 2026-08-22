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
}
