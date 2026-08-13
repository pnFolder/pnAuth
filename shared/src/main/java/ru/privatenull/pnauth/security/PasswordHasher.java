package ru.privatenull.pnauth.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import ru.privatenull.pnauth.storage.PasswordHash;
import ru.privatenull.pnauth.config.AuthSettings;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static PasswordHash hash(String password, AuthSettings settings) {
        return switch (settings.hashAlgorithm()) {
            case BCRYPT -> new PasswordHash("BCRYPT", "", BCrypt.withDefaults()
                    .hashToString(settings.bcryptCost(), password.toCharArray()), settings.bcryptCost());
            case ARGON2 -> hashArgon2(password, settings);
            case PBKDF2 -> hashPbkdf2(password, settings.hashIterations());
        };
    }

    public static boolean matches(String password, PasswordHash expected) {
        return switch (expected.algorithm().toUpperCase(Locale.ROOT)) {
            case "BCRYPT" -> BCrypt.verifyer().verify(password.toCharArray(), expected.hash()).verified;
            case "ARGON2" -> verifyArgon2(password, expected.hash());
            case "PBKDF2" -> matchesPbkdf2(password, expected);
            case "SHA256" -> sha256Hex(password).equalsIgnoreCase(expected.hash());
            case "SHA256_AUTHME" -> verifyAuthMeSha(password, expected.hash());
            default -> false;
        };
    }

    private static PasswordHash hashPbkdf2(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PasswordHash.pbkdf2(
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derive(password, salt, iterations)),
                iterations
        );
    }

    private static boolean matchesPbkdf2(String password, PasswordHash expected) {
        byte[] salt = Base64.getDecoder().decode(expected.salt());
        byte[] actual = derive(password, salt, expected.iterations());
        byte[] stored = Base64.getDecoder().decode(expected.hash());
        return MessageDigest.isEqual(actual, stored);
    }

    private static PasswordHash hashArgon2(String password, AuthSettings settings) {
        Argon2 argon2 = Argon2Factory.create();
        char[] chars = password.toCharArray();
        try {
            String hash = argon2.hash(
                    settings.argon2Iterations(),
                    settings.argon2Memory(),
                    settings.argon2Parallelism(),
                    chars
            );
            return PasswordHash.argon2(hash, settings.argon2Iterations());
        } finally {
            argon2.wipeArray(chars);
        }
    }

    private static boolean verifyArgon2(String password, String hash) {
        Argon2 argon2 = Argon2Factory.create();
        char[] chars = password.toCharArray();
        try {
            return argon2.verify(hash, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    private static boolean verifyAuthMeSha(String password, String hash) {
        if (hash.startsWith("$SHA$")) {
            String[] parts = hash.split("\\$");
            if (parts.length != 4) return false;
            return parts[3].equalsIgnoreCase(hex(sha256(sha256(password) + parts[2])));
        }
        return hex(sha256(password)).equalsIgnoreCase(hash);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256Hex(String value) {
        return hex(sha256(value));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2 is unavailable", exception);
        } finally {
            specification.clearPassword();
        }
    }
}
