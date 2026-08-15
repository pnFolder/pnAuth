package ru.privatenull.pnauth.api

enum class AuthStatus {
    NOT_LOADED,
    UNREGISTERED,
    UNAUTHENTICATED,
    TOTP_PENDING,
    AUTHENTICATED
}
