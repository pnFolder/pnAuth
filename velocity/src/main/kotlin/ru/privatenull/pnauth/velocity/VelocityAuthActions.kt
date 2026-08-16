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
) : ru.privatenull.pnauth.platform.adapter.PlatformAuthBridgeAdapter {

    override fun authenticated(uniqueId: UUID) {
        authenticated(uniqueId, false)
    }

    override fun authenticated(uniqueId: UUID, isRegistration: Boolean) {
        val player = proxy.getPlayer(uniqueId).orElse(null)
        authenticated(player, isRegistration)
    }

    override fun authenticated(username: String) {
        authenticated(proxy.getPlayer(username).orElse(null), false)
    }

    private fun authenticated(player: Player?, isRegistration: Boolean = false) {
        if (player == null) return

        // Display success Title & Subtitle with gradient
        val titleKey = if (isRegistration) "title.register.success" else "title.login.success"
        val subtitleKey = if (isRegistration) "subtitle.register.success" else "subtitle.login.success"
        val titleComp = VelocityMessages.component(messages.text(titleKey), messageFormat)
        val subtitleComp = VelocityMessages.component(messages.text(subtitleKey), messageFormat)
        val titleObj = net.kyori.adventure.title.Title.title(
            titleComp,
            subtitleComp,
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofMillis(2000),
                java.time.Duration.ofMillis(500)
            )
        )
        player.showTitle(titleObj)

        if (ru.privatenull.pnauth.dev.DevFlags.STAY_ON_AUTH_SERVER) {
            org.slf4j.LoggerFactory.getLogger("pnAuth")
                .info("[pnAuth-Dev] Player {} authenticated (Dev Mode STAY_ON_AUTH_SERVER active). Remaining on auth server.", player.username)
            return
        }

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
