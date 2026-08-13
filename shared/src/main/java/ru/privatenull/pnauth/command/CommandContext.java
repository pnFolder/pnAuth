package ru.privatenull.pnauth.command;

import java.util.List;

public record CommandContext(CommandSource source, String command, List<String> arguments) {
    public CommandContext {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        command = command == null ? "" : command;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
