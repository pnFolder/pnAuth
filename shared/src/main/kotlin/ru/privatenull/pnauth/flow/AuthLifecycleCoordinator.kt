package ru.privatenull.pnauth.flow

import ru.privatenull.pnauth.api.AdmissionDecision
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.event.PreAuthOperationEvent
import ru.privatenull.pnauth.extension.AuthOperation
import ru.privatenull.pnauth.extension.AuthOperationContext
import ru.privatenull.pnauth.policy.AuthAccessService
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Platform-neutral application layer. Proxy listeners translate native events into these methods
 * and only apply the returned decision; authentication policy remains in shared.
 */
class AuthLifecycleCoordinator(
    private val auth: AuthApi,
    private val access: AuthAccessService
) {
    fun admit(username: String, ip: String, onlineFromIp: Int): CompletionStage<AdmissionDecision> {
        return auth.checkAdmission(username, ip, onlineFromIp)
    }

    fun join(player: PlayerConnection): CompletionStage<JoinDecision> {
        return auth.onJoin(player.uniqueId, player.username, player.ip)
            .thenApply { status ->
                JoinDecision(
                    status,
                    if (auth.isAuthenticated(player.uniqueId)) JoinDecision.Route.BACKEND else JoinDecision.Route.AUTH_SERVER
                )
            }
    }

    fun quit(uniqueId: UUID) {
        auth.onQuit(uniqueId)
    }

    fun command(uniqueId: UUID, commandLine: String?): AuthAccessService.AccessDecision {
        if (cancelled(AuthOperation.COMMAND, uniqueId, mapOf("command" to commandRoot(commandLine)))) {
            return AuthAccessService.AccessDecision.DENY
        }
        return access.command(uniqueId, commandLine)
    }

    fun chat(uniqueId: UUID): AuthAccessService.AccessDecision {
        if (cancelled(AuthOperation.CHAT, uniqueId, emptyMap())) return AuthAccessService.AccessDecision.DENY
        return access.chat(uniqueId)
    }

    fun server(uniqueId: UUID, serverName: String?): AuthAccessService.ServerAccessDecision {
        if (cancelled(AuthOperation.SERVER_ACCESS, uniqueId, mapOf("server" to (serverName ?: "")))) {
            return AuthAccessService.ServerAccessDecision.REDIRECT_TO_AUTH
        }
        return access.server(uniqueId, serverName ?: "")
    }

    fun authServerName(): String = access.authServerName()
    fun blockedMessage(): String = access.blockedMessage()
    fun authServerMissingMessage(): String = access.authServerMissingMessage()
    fun message(key: String): String = access.message(key)

    private fun cancelled(operation: AuthOperation, uniqueId: UUID, attributes: Map<String, String>): Boolean {
        val user = auth.user(uniqueId).orElse(null)
        val event = PreAuthOperationEvent(
            AuthOperationContext(
                operation,
                uniqueId,
                user?.username ?: "",
                user?.lastIp,
                attributes
            )
        )
        auth.events().publish(event)
        return event.cancelled()
    }

    companion object {
        private fun commandRoot(commandLine: String?): String {
            var value = commandLine?.trim() ?: ""
            if (value.startsWith("/")) value = value.substring(1)
            val separator = value.indexOf(' ')
            return (if (separator < 0) value else value.substring(0, separator)).lowercase(Locale.ROOT)
        }
    }
}
