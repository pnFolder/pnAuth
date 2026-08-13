package ru.privatenull.pnauth.command;

import java.util.List;

public record CommandSpec(String name, List<String> aliases) {
    public CommandSpec {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
