package ru.privatenull.pnauth.message;

import java.util.Locale;

public enum MessageFormat {
    LEGACY,
    MINI_MESSAGE,
    JSON,
    PLAIN;

    public static MessageFormat parse(String value) {
        if (value == null || value.isBlank()) return LEGACY;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "legacy", "ampersand", "section" -> LEGACY;
            case "mini", "minimessage", "mini_message" -> MINI_MESSAGE;
            case "json", "component", "minecraft_json" -> JSON;
            case "plain", "text", "plain_text" -> PLAIN;
            default -> throw new IllegalArgumentException("Unknown message format: " + value);
        };
    }
}
