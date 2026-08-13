package ru.privatenull.pnauth.security;

public enum HashAlgorithm {
    PBKDF2,
    BCRYPT,
    ARGON2;

    public static HashAlgorithm parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Hash algorithm is blank");
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
