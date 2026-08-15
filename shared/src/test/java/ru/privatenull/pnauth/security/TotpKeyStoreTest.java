package ru.privatenull.pnauth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TotpKeyStoreTest {
    @Test
    void createsAndReusesOneKey(@TempDir Path directory) throws Exception {
        Path keyFile = directory.resolve("totp.key");
        byte[] created = TotpKeyStore.loadOrCreate(keyFile);

        assertEquals(32, created.length);
        assertArrayEquals(created, TotpKeyStore.loadOrCreate(keyFile));
    }

    @Test
    void rejectsMalformedExistingKey(@TempDir Path directory) throws Exception {
        Path keyFile = directory.resolve("totp.key");
        Files.write(keyFile, new byte[10]);

        assertThrows(java.io.IOException.class, () -> TotpKeyStore.loadOrCreate(keyFile));
    }
}
