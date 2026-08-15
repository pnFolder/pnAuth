package ru.privatenull.pnauth.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Set;

public final class TotpKeyStore {
    private TotpKeyStore() {
    }

    public static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return read(file);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            Files.write(file, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownerOnly(file);
            return key;
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // A second proxy startup won the race; both instances must use its key.
            return read(file);
        }
    }

    private static byte[] read(Path file) throws IOException {
        byte[] key = Files.readAllBytes(file);
        if (key.length != 32) {
            throw new IOException("TOTP key must contain exactly 32 bytes");
        }
        return key;
    }

    private static void ownerOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are managed by the server host; POSIX permissions are unavailable.
        }
    }
}
