package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.ProxiedPlayer
import ru.privatenull.pnauth.command.AuthPlatformBridge
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import java.net.InetSocketAddress
import java.util.UUID

internal class BungeeAuthActions(
    private val proxy: ProxyServer,
    private val settings: ProxySettings,
    private val messages: AuthMessages
) : AuthPlatformBridge {

    override fun authenticated(uniqueId: UUID) {
        val player = proxy.getPlayer(uniqueId)
        authenticated(player)
    }

    override fun authenticated(username: String) {
        authenticated(proxy.getPlayer(username))
    }

    private fun authenticated(player: ProxiedPlayer?) {
        if (player == null) return
        if (!settings.hasBackendServer()) return
        val target = target(player) ?: run {
            player.disconnect(BungeeMessages.component(messages.text("access.backend_missing"), messages.format()))
            return
        }
        val currentServer = player.server?.info
        if (target == currentServer) return
        player.connect(target) { success, _ ->
            if (!java.lang.Boolean.TRUE.equals(success) && player.isConnected) {
                player.disconnect(BungeeMessages.component(messages.text("access.backend_missing"), messages.format()))
            }
        }
    }

    override fun loggedOut(uniqueId: UUID) {
        disconnect(uniqueId, "logout.disconnect")
    }

    override fun accountDeleted(uniqueId: UUID) {
        disconnect(uniqueId, "unregister.disconnect")
    }

    override fun accountDeleted(username: String) {
        val player = proxy.getPlayer(username)
        player?.disconnect(BungeeMessages.component(messages.text("unregister.disconnect"), messages.format()))
    }

    override fun broadcast(message: String) {
        proxy.players.forEach { player ->
            player.sendMessage(
                *BungeeMessages.components(
                    messages.text("broadcast.message", mapOf("message" to message)),
                    messages.format()
                )
            )
        }
    }

    private fun disconnect(uniqueId: UUID, key: String) {
        val player = proxy.getPlayer(uniqueId)
        player?.disconnect(BungeeMessages.component(messages.text(key), messages.format()))
    }

    private fun target(player: ProxiedPlayer): ServerInfo? {
        var name = settings.backendServer
        val host: InetSocketAddress? = player.pendingConnection.virtualHost
        if (host != null) {
            name = settings.forcedHosts.getOrDefault(host.hostString.lowercase(), name)
        }
        return proxy.getServerInfo(name)
    }
}
