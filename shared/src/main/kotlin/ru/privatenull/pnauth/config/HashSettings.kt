package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.security.HashAlgorithm

@JvmRecord
data class HashSettings(
    val algorithm: HashAlgorithm,
    val pbkdf2Iterations: Int,
    val bcryptCost: Int,
    val argon2Iterations: Int,
    val argon2Memory: Int,
    val argon2Parallelism: Int
) {
    init {
        requireNotNull(algorithm) { "algorithm must not be null" }
        require(pbkdf2Iterations >= 10_000) { "pbkdf2Iterations is too small" }
        require(bcryptCost in 4..31) { "bcryptCost must be between 4 and 31" }
        require(argon2Iterations >= 1 && argon2Memory >= 8 && argon2Parallelism >= 1) {
            "Invalid Argon2 settings"
        }
    }

    companion object {
        @JvmStatic
        fun defaults(): HashSettings {
            return HashSettings(HashAlgorithm.PBKDF2, 600_000, 12, 2, 65_536, 1)
        }
    }
}
