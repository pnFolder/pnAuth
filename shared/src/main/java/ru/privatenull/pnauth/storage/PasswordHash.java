package ru.privatenull.pnauth.storage;

public record PasswordHash(String algorithm, String salt, String hash, int iterations) {
    public PasswordHash(String salt, String hash, int iterations) {
        this("PBKDF2", salt, hash, iterations);
    }

    public static PasswordHash pbkdf2(String salt, String hash, int iterations) {
        return new PasswordHash("PBKDF2", salt, hash, iterations);
    }

    public static PasswordHash bcrypt(String hash, int cost) {
        return new PasswordHash("BCRYPT", "", hash, cost);
    }

    public static PasswordHash argon2(String hash, int iterations) {
        return new PasswordHash("ARGON2", "", hash, iterations);
    }

    public static PasswordHash legacy(String algorithm, String hash) {
        return new PasswordHash(algorithm, "", hash, 0);
    }
}
