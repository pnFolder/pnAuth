package ru.privatenull.pnauth.config;

import java.time.Duration;
import java.util.List;

/** Visual settings for the title displayed while a password operation is running. */
public record ProcessingTitleSettings(boolean enabled, Animation animation, Timings timings) {
    public ProcessingTitleSettings {
        animation = animation == null ? Animation.defaults() : animation;
        timings = timings == null ? Timings.defaults() : timings;
    }

    public record Animation(Type type, List<String> colors, int frameCount) {
        public Animation {
            type = type == null ? Type.GRADIENT : type;
            colors = colors == null ? List.of() : List.copyOf(colors);
            if (type == Type.GRADIENT && colors.size() < 2) throw new IllegalArgumentException("A gradient requires at least two colors");
            for (String color : colors) if (color == null || !color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid gradient color: " + color);
            if (frameCount < 1 || frameCount > 120) throw new IllegalArgumentException("frameCount must be between 1 and 120");
        }
        public static Animation defaults() { return new Animation(Type.GRADIENT, List.of("#ff4ecd", "#8b5cf6", "#38bdf8"), 12); }
    }

    public record Timings(Duration frameInterval, Duration minimumDisplay, Duration resultFadeIn,
                          Duration resultDisplay, Duration resultFadeOut, Duration fadeIn,
                          Duration stay, Duration fadeOut) {
        public Timings {
            frameInterval = positive(frameInterval, "frameInterval");
            minimumDisplay = nonNegative(minimumDisplay, "minimumDisplay");
            resultFadeIn = nonNegative(resultFadeIn, "resultFadeIn");
            resultDisplay = nonNegative(resultDisplay, "resultDisplay");
            resultFadeOut = nonNegative(resultFadeOut, "resultFadeOut");
            fadeIn = nonNegative(fadeIn, "fadeIn");
            stay = positive(stay, "stay");
            fadeOut = nonNegative(fadeOut, "fadeOut");
        }
        public static Timings defaults() { return new Timings(Duration.ofMillis(120), Duration.ofMillis(2500),
                Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(500), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ZERO); }
        private static Duration positive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }
        private static Duration nonNegative(Duration value, String name) {
            if (value == null || value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
            return value;
        }
    }

    public enum Type { NONE, GRADIENT }
}
