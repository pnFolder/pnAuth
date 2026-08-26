package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.routing.ServerBalancerFactory
import java.util.UUID

internal class VelocityAuthActions(
    private val proxy: ProxyServer,
    private val settings: ProxySettings,
    private val messages: AuthMessages,
    private val messageFormat: MessageFormat,
    private val proxyAdapter: Proxy? = null
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

        if (!settings.hasBackendServer()) return
        if (player.currentServer.isEmpty) return

        var targets = settings.getEffectiveBackendServers()
        val virtualHost = player.virtualHost.orElse(null)
        if (virtualHost != null) {
            val forced = settings.forcedHosts[virtualHost.hostString.lowercase()]
            if (forced != null) targets = listOf(forced)
        }
        val balancer = ServerBalancerFactory.create(settings.balancerMode, settings.maxPlayersPerServer, settings.serverLimits)
        val selected = balancer.selectServer(targets, proxyAdapter).orElse(settings.backendServer)
        val target = proxy.getServer(selected).orElse(null)

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
