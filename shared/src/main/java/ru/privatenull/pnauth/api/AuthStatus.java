package ru.privatenull.pnauth.api;

public enum AuthStatus {
    NOT_LOADED,
    UNREGISTERED,
    UNAUTHENTICATED,
    TOTP_PENDING,
    AUTHENTICATED
}
