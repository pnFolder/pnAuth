package ru.privatenull.pnauth.policy

import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import java.util.Locale
import java.util.UUID

class AuthAccessService(
    private val auth: AuthApi,
    private val proxySettings: ProxySettings,
    private val accessSettings: AccessSettings,
    private val messages: AuthMessages
) {
    fun command(uniqueId: UUID, commandLine: String?): AccessDecision {
        return if (auth.isAuthenticated(uniqueId) || accessSettings.unauthenticatedCommands.contains(commandName(commandLine))) {
            AccessDecision.ALLOW
        } else {
            AccessDecision.DENY
        }
    }

    fun chat(uniqueId: UUID): AccessDecision {
        return if (!accessSettings.blockChat || auth.isAuthenticated(uniqueId)) {
            AccessDecision.ALLOW
        } else {
            AccessDecision.DENY
        }
    }

    fun server(uniqueId: UUID, serverName: String): ServerAccessDecision {
        return if (!proxySettings.requireServerAuth || auth.isAuthenticated(uniqueId) || proxySettings.isAuthServer(serverName)) {
            ServerAccessDecision.ALLOW
        } else {
            ServerAccessDecision.REDIRECT_TO_AUTH
        }
    }

    fun blockedMessage(): String {
        return messages.text("access.blocked")
    }

    fun message(key: String): String {
        return messages.text(key)
    }

    fun authServerMissingMessage(): String {
        return messages.text(
            "access.auth_server_missing",
            mapOf("server" to proxySettings.authServers.joinToString(", "))
        )
    }

    fun authServerName(): String = proxySettings.authServer

    fun authServerNames(): List<String> = proxySettings.authServers

    fun isAuthServer(serverName: String?): Boolean = proxySettings.isAuthServer(serverName)

    enum class AccessDecision {
        ALLOW,
        DENY
    }

    enum class ServerAccessDecision {
        ALLOW,
        REDIRECT_TO_AUTH
    }

    companion object {
        private fun commandName(commandLine: String?): String {
            var command = commandLine?.trim()?.lowercase(Locale.ROOT) ?: ""
            if (command.startsWith("/")) {
                command = command.substring(1)
            }
            val separator = command.indexOf(' ')
            return if (separator < 0) command else command.substring(0, separator)
        }
    }
}
