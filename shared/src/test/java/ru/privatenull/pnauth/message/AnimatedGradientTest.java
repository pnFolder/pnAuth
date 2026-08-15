package ru.privatenull.pnauth.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatedGradientTest {
    private static final List<String> COLORS = List.of("#ff0000", "#0000ff");

    @Test
    void movesGradientBetweenFrames() {
        String first = AnimatedGradient.frame("Login", MessageFormat.LEGACY, COLORS, 0, 12);
        String second = AnimatedGradient.frame("Login", MessageFormat.LEGACY, COLORS, 1, 12);
        assertNotEquals(first, second);
        assertTrue(first.contains("&x"));
    }

    @Test
    void producesValidComponentJson() {
        String frame = AnimatedGradient.frame("Вход", MessageFormat.JSON, COLORS, 0, 12);
        assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame));
        assertTrue(frame.contains("\"color\":\"#"));
    }
}
