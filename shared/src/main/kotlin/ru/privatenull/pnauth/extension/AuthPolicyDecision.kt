package ru.privatenull.pnauth.extension

import java.time.Duration

@JvmRecord
data class AuthPolicyDecision(
    val type: Type,
    val provider: String,
    val message: String,
    val lifetime: Duration
) {
    enum class Type { ALLOW, DENY, REQUIRE_VERIFICATION }

    companion object {
        @JvmStatic
        fun allow(): AuthPolicyDecision = AuthPolicyDecision(Type.ALLOW, "", "", Duration.ZERO)

        @JvmStatic
        fun deny(message: String): AuthPolicyDecision = AuthPolicyDecision(Type.DENY, "", message, Duration.ZERO)

        @JvmStatic
        fun requireVerification(provider: String?, message: String, lifetime: Duration?): AuthPolicyDecision {
            require(!provider.isNullOrBlank()) { "provider is required" }
            val effectiveLifetime = if (lifetime == null || lifetime.isNegative || lifetime.isZero) {
                Duration.ofMinutes(5)
            } else {
                lifetime
            }
            return AuthPolicyDecision(Type.REQUIRE_VERIFICATION, provider, message, effectiveLifetime)
        }
    }
}
