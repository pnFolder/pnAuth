package ru.privatenull.pnauth.limbo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import ru.privatenull.pnauth.config.LimboSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PicoLimboConfigStore {
    private static final ObjectMapper TOML = new ObjectMapper(new TomlFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PicoLimboConfig load(Path file) throws IOException {
        if (Files.notExists(file)) {
            throw new IOException("Limbo server.toml is missing: " + file
                    + ". Create it using the Limbo configuration documentation.");
        }
        return TOML.readValue(file.toFile(), PicoLimboConfig.class);
    }
}
