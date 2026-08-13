package ru.privatenull.pnauth.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

public final class TotpKeyStore {
    private TotpKeyStore() {
    }

    public static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] key = Files.readAllBytes(file);
            if (key.length != 32) {
                throw new IOException("TOTP key must contain exactly 32 bytes");
            }
            return key;
        }
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.write(file, key);
        return key;
    }
}
