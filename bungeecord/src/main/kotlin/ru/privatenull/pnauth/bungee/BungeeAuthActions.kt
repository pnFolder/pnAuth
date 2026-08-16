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
        authenticated(uniqueId, false)
    }

    override fun authenticated(uniqueId: UUID, isRegistration: Boolean) {
        val player = proxy.getPlayer(uniqueId)
        authenticated(player, isRegistration)
    }

    override fun authenticated(username: String) {
        authenticated(proxy.getPlayer(username), false)
    }

    private fun authenticated(player: ProxiedPlayer?, isRegistration: Boolean = false) {
        if (player == null) return

        // Display success Title & Subtitle with gradient via native BungeeComponentAdapter
        val titleKey = if (isRegistration) "title.register.success" else "title.login.success"
        val subtitleKey = if (isRegistration) "subtitle.register.success" else "subtitle.login.success"
        val titleComp = BungeeMessages.component(messages.text(titleKey), messages.format())
        val subtitleComp = BungeeMessages.component(messages.text(subtitleKey), messages.format())
        val titleObj = proxy.createTitle()
            .title(titleComp)
            .subTitle(subtitleComp)
            .fadeIn(10)
            .stay(40)
            .fadeOut(10)
        player.sendTitle(titleObj)

        if (ru.privatenull.pnauth.dev.DevFlags.STAY_ON_AUTH_SERVER) {
            proxy.logger.info("[pnAuth-Dev] Player ${player.name} authenticated (Dev Mode STAY_ON_AUTH_SERVER active). Remaining on auth server.")
            return
        }

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
