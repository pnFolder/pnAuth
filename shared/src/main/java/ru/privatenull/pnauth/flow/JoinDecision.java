package ru.privatenull.pnauth.flow;
import ru.privatenull.pnauth.api.AuthStatus;
public record JoinDecision(AuthStatus status, Route route) {
    public enum Route { AUTH_SERVER, BACKEND }
}
