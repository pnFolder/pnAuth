package ru.privatenull.pnauth.api

data class AdmissionDecision(
    val allowed: Boolean,
    val forceOnlineMode: Boolean,
    val reason: Reason
) {
    enum class Reason {
        ALLOWED,
        ONLINE_IP_LIMIT,
        REGISTERED_IP_LIMIT,
        BANNED,
        DATABASE_ERROR,
        POLICY_DENIED
    }
}
