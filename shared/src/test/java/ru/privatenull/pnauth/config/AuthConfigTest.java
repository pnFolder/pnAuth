package ru.privatenull.pnauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ru.privatenull.pnauth.message.MessageFormat;

class AuthConfigTest {
    @Test
    void createsReadableDefaultConfiguration(@TempDir Path directory) throws Exception {
        AuthConfig config = AuthConfig.load(
                directory.resolve("config.yml"),
                "jdbc:sqlite:" + directory.resolve("fallback.db")
        );

        assertEquals("ru", config.locale());
        assertTrue(config.storage().url().endsWith("auth.db"));
        assertTrue(config.security().isUsernameValid("Player_123"));
        assertFalse(config.security().isUsernameValid("bad name"));
        String generated = Files.readString(directory.resolve("config.yml"));
        assertTrue(generated.contains("servers:"));
        assertTrue(generated.contains("security:"));
        assertTrue(generated.contains("pnAuth language"));
        assertTrue(generated.contains("limbo:"));
        assertTrue(generated.contains("messages:"));
        assertEquals(MessageFormat.LEGACY, config.messageFormat());
    }

    @Test
    void readsCustomUsernameRule(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, ""
                + "locale: en\n"
                + "messages:\n"
                + "  format: MINI_MESSAGE\n"
                + "database:\n"
                + "  type: SQLITE\n"
                + "  file: auth.db\n"
                + "validation:\n"
                + "  username-pattern: '^player_[0-9]+$'\n");

        AuthConfig config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"));

        assertEquals("en", config.locale());
        assertEquals(MessageFormat.MINI_MESSAGE, config.messageFormat());
        assertTrue(config.security().isUsernameValid("player_42"));
        assertFalse(config.security().isUsernameValid("Player_42"));
    }

    @Test
    void allowsDisablingAuthenticationReminders(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "features:\n  session:\n    reminder-seconds: 0\n");

        AuthConfig config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"));

        assertTrue(config.features().reminderInterval().isZero());
    }
}
