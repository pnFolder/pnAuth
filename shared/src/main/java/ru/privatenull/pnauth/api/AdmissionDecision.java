package ru.privatenull.pnauth.api;

public record AdmissionDecision(boolean allowed, boolean forceOnlineMode, Reason reason) {
    public enum Reason {
        ALLOWED,
        ONLINE_IP_LIMIT,
        REGISTERED_IP_LIMIT,
        BANNED,
        DATABASE_ERROR,
        POLICY_DENIED
    }
}
