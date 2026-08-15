package ru.privatenull.pnauth.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record AccessSettings(
        boolean blockChat,
        Set<String> unauthenticatedCommands
) {
    public AccessSettings {
        unauthenticatedCommands = unauthenticatedCommands == null
                ? Set.of()
                : Set.copyOf(normalize(unauthenticatedCommands));
    }

    public static AccessSettings defaults() {
        return new AccessSettings(
                true,
                Set.of("auth", "pnauth", "register", "reg", "login", "l", "logout",
                        "changepassword", "changepass", "totp", "2fa", "status")
        );
    }

    private static Set<String> normalize(Set<String> commands) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String command : commands) {
            if (command != null && !command.isBlank()) {
                normalized.add(command.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
            }
        }
        return normalized;
    }
}
