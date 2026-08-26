package ru.privatenull.pnauth.storage

data class PasswordHash @JvmOverloads constructor(
    val algorithm: String,
    val salt: String,
    val hash: String,
    val iterations: Int
) {
    constructor(salt: String, hash: String, iterations: Int) : this("PBKDF2", salt, hash, iterations)

    fun algorithm(): String = algorithm
    fun salt(): String = salt
    fun hash(): String = hash
    fun iterations(): Int = iterations

    companion object {
        @JvmStatic
        fun pbkdf2(salt: String, hash: String, iterations: Int): PasswordHash {
            return PasswordHash("PBKDF2", salt, hash, iterations)
        }

        @JvmStatic
        fun bcrypt(hash: String, cost: Int): PasswordHash {
            return PasswordHash("BCRYPT", "", hash, cost)
        }

        @JvmStatic
        fun argon2(hash: String, iterations: Int): PasswordHash {
            return PasswordHash("ARGON2", "", hash, iterations)
        }

        @JvmStatic
        fun legacy(algorithm: String, hash: String): PasswordHash {
            return PasswordHash(algorithm, "", hash, 0)
        }
    }
}
