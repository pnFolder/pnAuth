package ru.privatenull.pnauth.config;

import java.time.Duration;
import java.util.regex.Pattern;
import ru.privatenull.pnauth.security.HashAlgorithm;

public record AuthSettings(
        int minPasswordLength,
        int maxPasswordLength,
        int maxLoginAttempts,
        Duration lockoutDuration,
        String usernamePattern,
        HashSettings hashing
) {
    public AuthSettings(
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            Duration lockoutDuration,
            int hashIterations
    ) {
        this(minPasswordLength, maxPasswordLength, maxLoginAttempts, lockoutDuration,
                "^[A-Za-z0-9_]{3,16}$", new HashSettings(HashAlgorithm.PBKDF2, hashIterations, 12, 2, 65_536, 1));
    }

    public AuthSettings(
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            Duration lockoutDuration,
            int hashIterations,
            String usernamePattern,
            ru.privatenull.pnauth.security.HashAlgorithm hashAlgorithm,
            int bcryptCost,
            int argon2Iterations,
            int argon2Memory,
            int argon2Parallelism
    ) {
        this(minPasswordLength, maxPasswordLength, maxLoginAttempts, lockoutDuration,
                usernamePattern, new HashSettings(hashAlgorithm, hashIterations, bcryptCost, argon2Iterations,
                        argon2Memory, argon2Parallelism));
    }

    public AuthSettings {
        if (minPasswordLength < 1) {
            throw new IllegalArgumentException("minPasswordLength must be positive");
        }
        if (maxPasswordLength < minPasswordLength) {
            throw new IllegalArgumentException("maxPasswordLength must not be smaller than minPasswordLength");
        }
        if (maxLoginAttempts < 1) {
            throw new IllegalArgumentException("maxLoginAttempts must be positive");
        }
        if (lockoutDuration.isNegative() || lockoutDuration.isZero()) {
            throw new IllegalArgumentException("lockoutDuration must be positive");
        }
        if (usernamePattern == null || usernamePattern.isBlank()) {
            throw new IllegalArgumentException("usernamePattern must not be blank");
        }
        if (hashing == null) {
            throw new IllegalArgumentException("hashing must not be null");
        }
        try {
            Pattern.compile(usernamePattern);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("usernamePattern is invalid", exception);
        }
    }

    public static AuthSettings defaults() {
        return new AuthSettings(8, 64, 5, Duration.ofSeconds(60),
                "^[A-Za-z0-9_]{3,16}$", HashSettings.defaults());
    }

    public int hashIterations() {
        return hashing.pbkdf2Iterations();
    }

    public ru.privatenull.pnauth.security.HashAlgorithm hashAlgorithm() {
        return hashing.algorithm();
    }

    public int bcryptCost() {
        return hashing.bcryptCost();
    }

    public int argon2Iterations() {
        return hashing.argon2Iterations();
    }

    public int argon2Memory() {
        return hashing.argon2Memory();
    }

    public int argon2Parallelism() {
        return hashing.argon2Parallelism();
    }

    public boolean isPasswordValid(String password) {
        return password != null
                && password.length() >= minPasswordLength
                && password.length() <= maxPasswordLength;
    }

    public boolean isUsernameValid(String username) {
        return username != null && Pattern.compile(usernamePattern).matcher(username).matches();
    }
}
