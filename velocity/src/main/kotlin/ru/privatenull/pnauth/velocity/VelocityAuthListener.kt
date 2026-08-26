package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import ru.privatenull.pnauth.api.AdmissionDecision
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.CommandRoots
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator
import ru.privatenull.pnauth.flow.JoinDecision
import ru.privatenull.pnauth.flow.PlayerConnection
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.policy.AuthAccessService
import ru.privatenull.pnauth.routing.ServerBalancerFactory
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CompletableFuture

class VelocityAuthListener(
    private val proxy: ProxyServer,
    private val auth: AuthApi,
    private val lifecycle: AuthLifecycleCoordinator,
    private val messageFormat: MessageFormat,
    private val embeddedAuthServer: RegisteredServer?,
    private val proxySettings: ProxySettings,
    private val dialogs: VelocityDialogCoordinator?,
    private val messages: AuthMessages,
    private val proxyAdapter: Proxy? = null
) {

    @Subscribe
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent): EventTask {
        val player = event.player
        val ip = ip(player.remoteAddress)
        return EventTask.resumeWhenComplete(
            lifecycle.join(PlayerConnection(player.uniqueId, player.username, ip))
                .handle { decision, error ->
                    if (error != null) {
                        event.setInitialServer(null)
                        player.disconnect(VelocityMessages.component(lifecycle.message("access.database"), messageFormat))
                        return@handle null
                    }
                    if (decision.route == JoinDecision.Route.BACKEND) {
                        val backend = resolveBackend(player)
                        event.setInitialServer(backend ?: authServer())
                    } else {
                        val target = authServer()
                        event.setInitialServer(target)
                        if (target == null) {
                            player.disconnect(VelocityMessages.component(lifecycle.authServerMissingMessage(), messageFormat))
                        }
                    }
                    null
                }.toCompletableFuture()
        )
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val player = event.player
        val current = player.currentServer
            .map { connection -> connection.serverInfo.name }.orElse("")
        if (!lifecycle.isAuthServer(current) || auth.isAuthenticated(player.uniqueId)) return
        val status = auth.status(player.uniqueId)
        val dialogShown = dialogs != null && dialogs.show(player, status)
        val dialogStatus = status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
        val protocol = player.protocolVersion.protocol
        val platformSupportsDialogs = dialogs != null && dialogs.available()
        if (!dialogShown && (!dialogStatus || auth.shouldUseCommandFallback(
                player.uniqueId, protocol, platformSupportsDialogs))) {
            player.sendMessage(VelocityMessages.component(messages.prompt(auth.status(player.uniqueId)), messageFormat))
        }
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent): EventTask {
        val ip = ip(event.connection.remoteAddress)
        val online = proxy.allPlayers.stream()
            .filter { player -> ip(player.remoteAddress) == ip }
            .count().toInt()
        val result = CompletableFuture<Void?>()
        lifecycle.admit(event.username, ip, online).whenComplete { decision, error ->
            if (error != null) {
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    VelocityMessages.component(lifecycle.blockedMessage(), messageFormat)
                )
            } else if (!decision.allowed) {
                val key = when (decision.reason) {
                    AdmissionDecision.Reason.BANNED -> "access.banned"
                    AdmissionDecision.Reason.ONLINE_IP_LIMIT -> "access.ip_online_limit"
                    AdmissionDecision.Reason.POLICY_DENIED -> "access.blocked"
                    else -> "access.ip_registered_limit"
                }
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    VelocityMessages.component(lifecycle.message(key), messageFormat)
                )
            } else if (decision.forceOnlineMode) {
                event.result = PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
            } else {
                event.result = PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
            }
            result.complete(null)
        }
        return EventTask.resumeWhenComplete(result)
    }

    @Subscribe
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        if (CommandRoots.isExactRoot(event.command, "_pnauthui")) return
        if (CommandRoots.isPasswordAuthenticationCommand(event.command) && dialogs != null
            && !dialogs.allowAuthenticationCommand(player)) {
            event.result = CommandExecuteEvent.CommandResult.denied()
            dialogs.requestCaptcha(player)
            return
        }
        if (lifecycle.command(player.uniqueId, event.command) == AuthAccessService.AccessDecision.DENY) {
            event.result = CommandExecuteEvent.CommandResult.denied()
            player.sendMessage(VelocityMessages.component(lifecycle.blockedMessage(), messageFormat))
        }
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (lifecycle.server(event.player.uniqueId, event.originalServer.serverInfo.name)
            == AuthAccessService.ServerAccessDecision.ALLOW) {
            return
        }
        val authServer = authServer()
        if (authServer == null) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
            event.player.sendMessage(VelocityMessages.component(lifecycle.authServerMissingMessage(), messageFormat))
            return
        }
        event.result = ServerPreConnectEvent.ServerResult.allowed(authServer)
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        if (!lifecycle.isAuthServer(event.server.serverInfo.name)) return
        if (auth.isAuthenticated(event.player.uniqueId)) {
            val backend = resolveBackend(event.player)
            if (backend != null) {
                event.result = KickedFromServerEvent.RedirectPlayer.create(backend)
                return
            }
        }
        event.result = KickedFromServerEvent.DisconnectPlayer.create(
            event.serverKickReason.orElse(
                VelocityMessages.component(lifecycle.authServerMissingMessage(), messageFormat)
            )
        )
    }

    private fun authServer(): RegisteredServer? {
        embeddedAuthServer?.let { if (proxySettings.isAuthServer(it.serverInfo.name)) return it }
        val targets = proxySettings.getEffectiveAuthServers()
        val balancer = ServerBalancerFactory.create(
            proxySettings.balancerMode,
            proxySettings.maxPlayersPerServer,
            proxySettings.serverLimits
        )
        val selected = balancer.selectServer(targets, proxyAdapter).orElse(null)
            ?: targets.firstOrNull()
            ?: return null
        return proxy.getServer(selected).orElse(null)
    }

    private fun resolveBackend(player: Player): RegisteredServer? {
        if (!proxySettings.hasBackendServer() && proxySettings.forcedHosts.isEmpty()) return null
        var targets = proxySettings.getEffectiveBackendServers()
        val forced = player.virtualHost
            .map { host -> proxySettings.forcedHosts[host.hostString.lowercase(Locale.ROOT)] }
            .orElse(null)
        if (!forced.isNullOrBlank()) targets = listOf(forced)
        if (targets.isEmpty()) return null

        val balancer = ServerBalancerFactory.create(
            proxySettings.balancerMode,
            proxySettings.maxPlayersPerServer,
            proxySettings.serverLimits
        )
        val selected = balancer.selectServer(targets, proxyAdapter).orElse(null)
            ?: targets.firstOrNull()
            ?: return null
        return proxy.getServer(selected).orElse(null)
    }

    companion object {
        private fun ip(address: InetSocketAddress?): String {
            if (address == null) return "unknown"
            val resolved: InetAddress? = address.address
            return resolved?.hostAddress ?: address.hostString
        }
    }
}
