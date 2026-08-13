package ru.privatenull.pnauth.command;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public record AuthCommandRequest(
        UUID uniqueId,
        String username,
        String command,
        List<String> arguments,
        Predicate<String> permissionChecker
) {
    public AuthCommandRequest(UUID uniqueId, String username, String command, List<String> arguments) {
        this(uniqueId, username, command, arguments, permission -> false);
    }

    public AuthCommandRequest {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        command = command == null ? "" : command;
        permissionChecker = permissionChecker == null ? permission -> false : permissionChecker;
    }

    public boolean isPlayer() {
        return uniqueId != null;
    }

    public boolean hasPermission(String permission) {
        return permissionChecker.test(permission);
    }
}
