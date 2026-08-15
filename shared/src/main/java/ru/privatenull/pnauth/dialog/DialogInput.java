package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import java.util.List;

/** A platform-neutral input displayed inside a player dialog. */
public sealed interface DialogInput permits DialogInput.Text, DialogInput.Toggle, DialogInput.Choice,
        DialogInput.NumberRange {
    String id();
    Component label();

    record Text(String id, Component label, boolean labelVisible, String initialValue, int maximumLength,
                int width, Multiline multiline) implements DialogInput {
        public record Multiline(Integer maximumLines, Integer height) { }
    }

    record Toggle(String id, Component label, boolean initialValue, String onTrue, String onFalse)
            implements DialogInput { }

    record Choice(String id, Component label, boolean labelVisible, int width, List<Option> options)
            implements DialogInput {
        public Choice { options = List.copyOf(options); }
        public record Option(String id, Component display, boolean initial) { }
    }

    /** Numeric slider matching Minecraft's number-range input. */
    record NumberRange(String id, Component label, String labelFormat, int width, float start, float end,
                       Float initial, Float step) implements DialogInput { }
}
