package ru.privatenull.pnauth.flow

import ru.privatenull.pnauth.api.AuthStatus

@JvmRecord
data class JoinDecision(val status: AuthStatus, val route: Route) {
    enum class Route { AUTH_SERVER, BACKEND }
}
