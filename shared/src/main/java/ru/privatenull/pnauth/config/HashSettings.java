package ru.privatenull.pnauth.config;

import ru.privatenull.pnauth.security.HashAlgorithm;

public record HashSettings(
        HashAlgorithm algorithm,
        int pbkdf2Iterations,
        int bcryptCost,
        int argon2Iterations,
        int argon2Memory,
        int argon2Parallelism
) {
    public HashSettings {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm must not be null");
        }
        if (pbkdf2Iterations < 10_000) {
            throw new IllegalArgumentException("pbkdf2Iterations is too small");
        }
        if (bcryptCost < 4 || bcryptCost > 31) {
            throw new IllegalArgumentException("bcryptCost must be between 4 and 31");
        }
        if (argon2Iterations < 1 || argon2Memory < 8 || argon2Parallelism < 1) {
            throw new IllegalArgumentException("Invalid Argon2 settings");
        }
    }

    public static HashSettings defaults() {
        return new HashSettings(HashAlgorithm.PBKDF2, 120_000, 12, 2, 65_536, 1);
    }
}
