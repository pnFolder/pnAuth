package ru.privatenull.pnauth.limbo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicoLimboConfigStoreTest {
    @Test
    void disablesForwardingForEmbeddedLoopbackServer(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:25566"

                [forwarding]
                method = "LEGACY"
                secret = "old-secret"

                [world]
                dimension = "overworld"
                """);

        PicoLimboConfigStore store = new PicoLimboConfigStore();
        store.prepareEmbedded(config);
        PicoLimboConfig parsed = store.load(config);

        assertEquals("NONE", parsed.forwarding.method);
        assertEquals("", parsed.forwarding.secret);
        String migrated = Files.readString(config);
        assertTrue(migrated.contains("[world]"));
        assertTrue(migrated.contains("forwarding.method = 'NONE'"));
        store.prepareEmbedded(config);
        assertEquals(migrated, Files.readString(config));
    }

    @Test
    void removesConflictingDottedAndTableForwarding(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.toml");
        Files.writeString(config, """
                bind = '127.0.0.1:25566'
                forwarding.method = 'LEGACY'
                forwarding.secret = ''

                [forwarding]
                method = "NONE"
                secret = ""
                """);

        PicoLimboConfigStore store = new PicoLimboConfigStore();
        store.prepareEmbedded(config);

        PicoLimboConfig parsed = store.load(config);
        assertEquals("NONE", parsed.forwarding.method);
        assertEquals(1, Files.readString(config).split("forwarding.method", -1).length - 1);
    }

    @Test
    void createsAndSynchronizesEmbeddedEndpoint(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.toml");
        PicoLimboConfigStore store = new PicoLimboConfigStore();

        store.prepareEmbedded(config, "127.0.0.1", 25577);
        PicoLimboConfig created = store.load(config);
        assertEquals("127.0.0.1", created.endpoint().host());
        assertEquals(25577, created.endpoint().port());
        assertEquals("NONE", created.forwarding.method);

        Files.writeString(config, Files.readString(config) + "\n[world]\ndimension = \"overworld\"\n");
        store.prepareEmbedded(config, "127.0.0.1", 25578);
        PicoLimboConfig updated = store.load(config);
        assertEquals(25578, updated.endpoint().port());
        assertTrue(Files.readString(config).contains("[world]"));
    }
}
