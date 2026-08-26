package ru.privatenull.pnauth.message

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimatedGradientTest {

    @Test
    fun movesGradientBetweenFrames() {
        val first = AnimatedGradient.frame("Login", MessageFormat.LEGACY, COLORS, 0, 12)
        val second = AnimatedGradient.frame("Login", MessageFormat.LEGACY, COLORS, 1, 12)
        assertNotEquals(first, second)
        assertTrue(first.contains("&x"))
    }

    @Test
    fun producesValidComponentJson() {
        val frame = AnimatedGradient.frame("Вход", MessageFormat.JSON, COLORS, 0, 12)
        assertDoesNotThrow { ObjectMapper().readTree(frame) }
        assertTrue(frame.contains("\"color\":\"#"))
    }

    companion object {
        private val COLORS = listOf("#ff0000", "#0000ff")
    }
}
