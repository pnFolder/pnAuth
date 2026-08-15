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
            case BCRYPT -> hashBcrypt(password, settings.bcryptCost());
            case ARGON2 -> hashArgon2(password, settings);
            case PBKDF2 -> hashPbkdf2(password, settings.hashIterations());
        };
    }

    public static boolean matches(String password, PasswordHash expected) {
        if (password == null || expected == null || expected.algorithm() == null || expected.hash() == null) return false;
        try {
            return switch (expected.algorithm().toUpperCase(Locale.ROOT)) {
                case "BCRYPT" -> verifyBcrypt(password, expected.hash());
                case "ARGON2" -> verifyArgon2(password, expected.hash());
                case "PBKDF2" -> matchesPbkdf2(password, expected);
                case "SHA256" -> sha256Hex(password).equalsIgnoreCase(expected.hash());
                case "SHA256_AUTHME" -> verifyAuthMeSha(password, expected.hash());
                default -> false;
            };
        } catch (RuntimeException exception) {
            // A corrupt or legacy database entry must never crash the login worker.
            return false;
        }
    }

    /**
     * Returns whether a successfully verified hash should be replaced with the configured
     * password scheme. This upgrades imported fast hashes without requiring every player
     * to reset a password.
     */
    public static boolean needsRehash(PasswordHash hash, AuthSettings settings) {
        if (hash == null || hash.algorithm() == null || settings == null) return false;
        String expectedAlgorithm = settings.hashAlgorithm().name();
        if (!expectedAlgorithm.equalsIgnoreCase(hash.algorithm())) return true;
        return switch (settings.hashAlgorithm()) {
            case PBKDF2 -> hash.iterations() != settings.hashIterations();
            case BCRYPT -> hash.iterations() != settings.bcryptCost();
            case ARGON2 -> hash.iterations() != settings.argon2Iterations()
                    || !hash.hash().startsWith("$argon2id$");
        };
    }

    private static PasswordHash hashBcrypt(String password, int cost) {
        char[] chars = password.toCharArray();
        try {
            return new PasswordHash("BCRYPT", "", BCrypt.withDefaults().hashToString(cost, chars), cost);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private static boolean verifyBcrypt(String password, String hash) {
        char[] chars = password.toCharArray();
        try {
            return BCrypt.verifyer().verify(chars, hash).verified;
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
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
        Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
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
        Argon2 argon2 = Argon2Factory.create(hash != null && hash.startsWith("$argon2i$")
                ? Argon2Factory.Argon2Types.ARGON2i : Argon2Factory.Argon2Types.ARGON2id);
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
