package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import ru.privatenull.pnauth.command.AuthPlatformBridge
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import java.util.UUID

internal class VelocityAuthActions(
    private val proxy: ProxyServer,
    private val settings: ProxySettings,
    private val messages: AuthMessages,
    private val messageFormat: MessageFormat
) : AuthPlatformBridge {

    override fun authenticated(uniqueId: UUID) {
        val player = proxy.getPlayer(uniqueId).orElse(null)
        authenticated(player)
    }

    override fun authenticated(username: String) {
        authenticated(proxy.getPlayer(username).orElse(null))
    }

    private fun authenticated(player: Player?) {
        if (player == null) return
        if (!settings.hasBackendServer()) return
        if (player.currentServer.isEmpty) return
        val serverName = player.virtualHost
            .map { host ->
                settings.forcedHosts[host.hostString.lowercase()] ?: settings.backendServer
            }
            .orElse(settings.backendServer)
        val target = proxy.getServer(serverName).orElse(null)
        if (target == null) {
            player.disconnect(VelocityMessages.component(messages.text("access.backend_missing"), messageFormat))
            return
        }
        if (player.currentServer.map { server -> server.server != target }.orElse(true)) {
            player.createConnectionRequest(target).connect()
        }
    }

    override fun loggedOut(uniqueId: UUID) {
        proxy.getPlayer(uniqueId).ifPresent { player ->
            player.disconnect(VelocityMessages.component(messages.text("logout.disconnect"), messageFormat))
        }
    }

    override fun accountDeleted(uniqueId: UUID) {
        proxy.getPlayer(uniqueId).ifPresent { player ->
            player.disconnect(VelocityMessages.component(messages.text("unregister.disconnect"), messageFormat))
        }
    }

    override fun accountDeleted(username: String) {
        proxy.getPlayer(username).ifPresent { player ->
            player.disconnect(VelocityMessages.component(messages.text("unregister.disconnect"), messageFormat))
        }
    }

    override fun broadcast(message: String) {
        proxy.allPlayers.forEach { player ->
            player.sendMessage(
                VelocityMessages.component(
                    messages.text("broadcast.message", mapOf("message" to message)),
                    messageFormat
                )
            )
        }
    }
}
