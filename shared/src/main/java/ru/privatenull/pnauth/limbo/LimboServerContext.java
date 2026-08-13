package ru.privatenull.pnauth.limbo;

import ru.privatenull.pnauth.config.LimboSettings;

import java.nio.file.Path;

public record LimboServerContext(Path dataDirectory, LimboSettings settings) {
    public LimboServerContext {
        if (dataDirectory == null || settings == null) {
            throw new IllegalArgumentException("Limbo context is incomplete");
        }
    }
}
