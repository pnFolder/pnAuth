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
        assertFalse(config.security().isPasswordValid("1234567"));
        assertTrue(config.proxy().requireServerAuth());
        String generated = Files.readString(directory.resolve("config.yml"));
        assertTrue(generated.contains("servers:"));
        assertTrue(generated.contains("security:"));
        assertTrue(generated.contains("Supported values: ru, en"));
        assertTrue(generated.contains("limbo:"));
        assertTrue(generated.contains("messages:"));
        assertEquals(MessageFormat.LEGACY, config.messageFormat());
    }

    @Test
    void configManagerCreatesAndUpdatesDocumentedConfiguration(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        PnAuthConfigManager manager = new PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"));

        AuthConfig created = manager.load();
        String generated = Files.readString(configFile);

        assertEquals("ru", created.locale());
        assertTrue(generated.contains("config-version"));
        assertTrue(generated.contains("Schema version"));

        Files.writeString(configFile, "locale: en\nmessages:\n  format: PLAIN\n");
        AuthConfig updated = manager.load();

        assertEquals("en", updated.locale());
        assertEquals(MessageFormat.PLAIN, updated.messageFormat());
        assertTrue(Files.readString(configFile).contains("database:"));
    }

    @Test
    void upgradesOlderSchemaOnceAndAddsNewTotpSettings(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "config-version: 1\nlocale: en\n");

        new PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db")).load();
        String upgraded = Files.readString(configFile);

        assertTrue(upgraded.contains("config-version: " + AuthConfig.CURRENT_SCHEMA_VERSION));
        assertTrue(upgraded.contains("setup-lifetime-seconds"));
        assertTrue(upgraded.contains("restore-on-same-ip"));
        assertEquals("config-version: 1\nlocale: en\n", Files.readString(configFile.resolveSibling("config.yml.bak")));
    }

    @Test
    void repairsCurrentSchemaWhenRequiredFieldWasRemoved(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, """
                config-version: 3
                locale: en
                features:
                  totp:
                    setup-lifetime-seconds: 300
                """);

        new PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db")).load();

        String upgraded = Files.readString(configFile);
        assertTrue(upgraded.contains("restore-on-same-ip"));
        assertTrue(Files.exists(configFile.resolveSibling("config.yml.bak")));
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
    void rejectsUnsupportedLocale(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "locale: de\n");

        java.io.IOException error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db")));

        assertTrue(error.getMessage().contains("Supported locales: ru, en"));
    }

    @Test
    void allowsDisablingAuthenticationReminders(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "features:\n  session:\n    reminder-seconds: 0\n");

        AuthConfig config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"));

        assertTrue(config.features().reminderInterval().isZero());
    }

    @Test
    void buildsDriverSpecificTlsDatabaseUrls(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, """
                database:
                  type: MYSQL
                  mysql:
                    host: db.example.test
                    port: 3306
                    database: pnauth
                    use-ssl: true
                    server-timezone: UTC
                """);
        assertTrue(AuthConfig.load(configFile, "").storage().url().contains("sslMode=VERIFY_IDENTITY"));

        Files.writeString(configFile, """
                database:
                  type: POSTGRESQL
                  postgresql:
                    host: db.example.test
                    port: 5432
                    database: pnauth
                    use-ssl: true
                """);
        String postgresUrl = AuthConfig.load(configFile, "").storage().url();
        assertTrue(postgresUrl.contains("sslmode=verify-full"));
        assertFalse(postgresUrl.contains("serverTimezone"));
    }

    @Test
    void migratesLegacyForkedPicoLimboDownload(@TempDir Path directory) throws Exception {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, """
                limbo:
                  download-base-url: "https://github.com/pnFolder/PicoLimbo/releases/download/v1.13.2-pn.2%2Bmc26.2/"
                  download-sha-256: "701ad39c987e01edc659198d166e91d91a3182b8ae7df3bcc7c8366629089e13"
                """);

        AuthConfig config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"));

        assertEquals(LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL, config.limbo().downloadBaseUrl());
        assertEquals(LimboSettings.OFFICIAL_DOWNLOAD_SHA256, config.limbo().downloadSha256());
        String migrated = Files.readString(configFile);
        assertTrue(migrated.contains("github.com/Quozul/PicoLimbo"));
        assertFalse(migrated.contains("github.com/pnFolder/PicoLimbo"));
    }
}
