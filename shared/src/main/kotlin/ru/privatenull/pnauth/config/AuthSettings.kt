package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.security.HashAlgorithm
import java.time.Duration
import java.util.regex.Pattern

@JvmRecord
data class AuthSettings @JvmOverloads constructor(
    val minPasswordLength: Int,
    val maxPasswordLength: Int,
    val maxLoginAttempts: Int,
    val lockoutDuration: Duration,
    val usernamePattern: String,
    val hashing: HashSettings
) {
    constructor(
        minPasswordLength: Int,
        maxPasswordLength: Int,
        maxLoginAttempts: Int,
        lockoutDuration: Duration,
        hashIterations: Int
    ) : this(
        minPasswordLength, maxPasswordLength, maxLoginAttempts, lockoutDuration,
        "^[A-Za-z0-9_]{3,16}$", HashSettings(HashAlgorithm.PBKDF2, hashIterations, 12, 2, 65_536, 1)
    )

    constructor(
        minPasswordLength: Int,
        maxPasswordLength: Int,
        maxLoginAttempts: Int,
        lockoutDuration: Duration,
        hashIterations: Int,
        usernamePattern: String,
        hashAlgorithm: HashAlgorithm,
        bcryptCost: Int,
        argon2Iterations: Int,
        argon2Memory: Int,
        argon2Parallelism: Int
    ) : this(
        minPasswordLength, maxPasswordLength, maxLoginAttempts, lockoutDuration,
        usernamePattern, HashSettings(hashAlgorithm, hashIterations, bcryptCost, argon2Iterations, argon2Memory, argon2Parallelism)
    )

    init {
        require(minPasswordLength >= 1) { "minPasswordLength must be positive" }
        require(maxPasswordLength >= minPasswordLength) { "maxPasswordLength must not be smaller than minPasswordLength" }
        require(maxLoginAttempts >= 1) { "maxLoginAttempts must be positive" }
        require(!lockoutDuration.isNegative && !lockoutDuration.isZero) { "lockoutDuration must be positive" }
        require(usernamePattern.isNotBlank()) { "usernamePattern must not be blank" }
        requireNotNull(hashing) { "hashing must not be null" }
        try {
            Pattern.compile(usernamePattern)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("usernamePattern is invalid", exception)
        }
    }

    fun hashIterations(): Int = hashing.pbkdf2Iterations
    fun hashAlgorithm(): HashAlgorithm = hashing.algorithm
    fun bcryptCost(): Int = hashing.bcryptCost
    fun argon2Iterations(): Int = hashing.argon2Iterations
    fun argon2Memory(): Int = hashing.argon2Memory
    fun argon2Parallelism(): Int = hashing.argon2Parallelism

    fun isPasswordValid(password: String?): Boolean {
        return password != null && password.length in minPasswordLength..maxPasswordLength
    }

    fun isUsernameValid(username: String?): Boolean {
        return username != null && Pattern.compile(usernamePattern).matcher(username).matches()
    }

    companion object {
        @JvmStatic
        fun defaults(): AuthSettings {
            return AuthSettings(
                8, 64, 5, Duration.ofSeconds(60),
                "^[A-Za-z0-9_]{3,16}$", HashSettings.defaults()
            )
        }
    }
}
