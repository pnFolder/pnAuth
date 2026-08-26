package ru.privatenull.pnauth.security

import java.util.Locale

enum class HashAlgorithm {
    PBKDF2,
    BCRYPT,
    ARGON2;

    companion object {
        @JvmStatic
        fun parse(value: String?): HashAlgorithm {
            require(!value.isNullOrBlank()) { "Hash algorithm is blank" }
            return valueOf(value.trim().uppercase(Locale.ROOT))
        }
    }
}
