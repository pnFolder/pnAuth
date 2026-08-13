package ru.privatenull.pnauth.config;

public record DialogSettings(
        boolean enabled,
        boolean fallbackToCommands,
        boolean allowPlayerPreference,
        int minClientProtocol
) {
    public DialogSettings {
        if (minClientProtocol < 0) {
            throw new IllegalArgumentException("minClientProtocol must not be negative");
        }
    }

    public static DialogSettings defaults() {
        return new DialogSettings(true, true, true, 771);
    }
}
