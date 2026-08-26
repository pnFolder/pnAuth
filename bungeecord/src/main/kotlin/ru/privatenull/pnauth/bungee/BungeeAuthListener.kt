@file:Suppress("DEPRECATION")
package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.ChatEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PreLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.event.TabCompleteEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import ru.privatenull.pnauth.api.AdmissionDecision
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandRoots
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator
import ru.privatenull.pnauth.flow.PlayerConnection
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.policy.AuthAccessService
import ru.privatenull.pnauth.routing.ServerBalancerFactory
import java.net.InetAddress
import java.net.InetSocketAddress

class BungeeAuthListener(
    private val proxy: ProxyServer,
    private val owner: Plugin,
    private val lifecycle: AuthLifecycleCoordinator,
    private val messages: AuthMessages,
    private val commands: CommandService,
    private val dialogs: BungeeDialogListener,
    private val proxySettings: ProxySettings,
    private val proxyAdapter: Proxy? = null
) : Listener {

    @EventHandler
    fun onPreLogin(event: PreLoginEvent) {
        val socketAddr = event.connection.socketAddress
        val ip = if (socketAddr is InetSocketAddress) ip(socketAddr) else "unknown"
        val online = proxy.players.stream()
            .filter { player -> ip(player.address) == ip }
            .count()
            .toInt()
        event.registerIntent(owner)
        lifecycle.admit(event.connection.name, ip, online).whenComplete { decision, error ->
            try {
                if (error != null) {
                    event.isCancelled = true
                    event.setReason(BungeeMessages.component(messages.text("access.database"), messages.format()))
                } else if (decision != null && !decision.allowed) {
                    event.isCancelled = true
                    val key = when (decision.reason) {
                        AdmissionDecision.Reason.BANNED -> "access.banned"
                        AdmissionDecision.Reason.ONLINE_IP_LIMIT -> "access.ip_online_limit"
                        AdmissionDecision.Reason.POLICY_DENIED -> "access.blocked"
                        else -> "access.ip_registered_limit"
                    }
                    event.setReason(BungeeMessages.component(messages.text(key), messages.format()))
                } else if (decision != null) {
                    event.connection.setOnlineMode(decision.forceOnlineMode)
                }
            } finally {
                event.completeIntent(owner)
            }
        }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        lifecycle.join(PlayerConnection(player.uniqueId, player.name, ip(player.address)))
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        lifecycle.quit(event.player.uniqueId)
    }

    @EventHandler
    fun onServerConnect(event: ServerConnectEvent) {
        if (lifecycle.server(event.player.uniqueId, event.target.name) == AuthAccessService.ServerAccessDecision.ALLOW) {
            return
        }
        val authServer = authServer()
        if (authServer == null) {
            event.isCancelled = true
            event.player.sendMessage(*BungeeMessages.components(lifecycle.authServerMissingMessage(), messages.format()))
            return
        }
        event.target = authServer
    }

    @EventHandler
    fun onChat(event: ChatEvent) {
        val sender = event.sender
        if (sender !is ProxiedPlayer) {
            return
        }
        if (event.isCommand && CommandRoots.isExactRoot(event.message, "_pnauthui")) return
        if (event.isCommand && CommandRoots.isPasswordAuthenticationCommand(event.message)
            && !dialogs.allowAuthenticationCommand(sender)
        ) {
            event.isCancelled = true
            dialogs.requestCaptcha(sender)
            return
        }
        if (!event.isCommand) {
            if (lifecycle.chat(sender.uniqueId) == AuthAccessService.AccessDecision.DENY) {
                event.isCancelled = true
                sendBlocked(sender)
            }
            return
        }
        if (lifecycle.command(sender.uniqueId, event.message) == AuthAccessService.AccessDecision.DENY) {
            event.isCancelled = true
            sendBlocked(sender)
        }
    }

    @EventHandler
    fun onTabComplete(event: TabCompleteEvent) {
        val sender = event.sender
        if (sender !is ProxiedPlayer) {
            return
        }
        val cursor = event.cursor.trim()
        val parts = cursor.split("\\s+".toRegex()).toTypedArray()
        if (parts.isEmpty()) {
            return
        }
        val command = if (parts[0].startsWith("/")) parts[0].substring(1) else parts[0]
        if (!command.equals("auth", ignoreCase = true) && !command.equals("pnauth", ignoreCase = true)) {
            return
        }
        event.suggestions.clear()
        event.suggestions.addAll(
            commands.suggest(
                CommandContext(
                    BungeeCommandSource(sender),
                    command,
                    parts.toList().subList(1, parts.size)
                )
            )
        )
    }

    private fun authServer(): ServerInfo? {
        val targets = proxySettings.getEffectiveAuthServers()
        val balancer = ServerBalancerFactory.create(
            proxySettings.balancerMode,
            proxySettings.maxPlayersPerServer,
            proxySettings.serverLimits
        )
        val selected = balancer.selectServer(targets, proxyAdapter).orElse(null)
            ?: targets.firstOrNull()
            ?: return null
        return proxy.getServerInfo(selected)
    }

    private fun sendBlocked(player: ProxiedPlayer) {
        player.sendMessage(*BungeeMessages.components(lifecycle.blockedMessage(), messages.format()))
    }

    companion object {
        private fun ip(address: InetSocketAddress?): String {
            if (address == null) return "unknown"
            val resolved: InetAddress? = address.address
            return resolved?.hostAddress ?: address.hostString
        }
    }
}
