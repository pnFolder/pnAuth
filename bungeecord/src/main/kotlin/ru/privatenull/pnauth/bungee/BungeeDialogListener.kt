package ru.privatenull.pnauth.bungee

import net.kyori.adventure.text.Component
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import net.md_5.bungee.event.EventHandler
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.PnPlatform
import ru.privatenull.pnauth.ui.AuthUiCoordinator
import ru.privatenull.pnauth.ui.AuthUiRenderer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Bungee lifecycle bridge; all authentication UI behavior lives in shared. */
class BungeeDialogListener internal constructor(
    private val plugin: Plugin,
    private val auth: AuthApi,
    commands: AuthCommandService,
    private val messages: AuthMessages,
    features: FeatureSettings,
    maxPasswordLength: Int,
    private val settings: ProxySettings,
    platform: PnPlatform,
    commandRegistry: CommandRegistry
) : Listener {

    private val coordinator: AuthUiCoordinator = AuthUiCoordinator(
        auth, commands, messages, features, maxPasswordLength,
        settings.authServer, platform, object : AuthUiRenderer {
            override fun render(key: String, replacements: Map<String, String>): Component {
                return BungeeMessages.adventureComponent(messages.text(key, replacements), messages.format)
            }

            override fun renderText(text: String): Component {
                return BungeeMessages.adventureComponent(text, messages.format)
            }
        }, commandRegistry,
        Consumer { message -> plugin.logger.info(message) }
    )

    private val pending: MutableMap<UUID, ScheduledTask> = ConcurrentHashMap()

    @EventHandler
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        plugin.logger.info("[pnAuth-DEBUG] onServerConnected: player=${player.name}, connectedServer=${event.server.info.name}, configuredAuthServer=${settings.authServer}")
        if (!event.server.info.name.equals(settings.authServer, ignoreCase = true)
            || auth.isAuthenticated(player.uniqueId)
        ) {
            coordinator.clear(player.uniqueId)
        } else scheduleWhenLoaded(player)
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        plugin.logger.info("[pnAuth-DEBUG] onPostLogin: player=${event.player.name}")
        scheduleWhenLoaded(event.player)
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        cancel(event.player.uniqueId)
        coordinator.clearSession(event.player.uniqueId)
    }

    fun allowAuthenticationCommand(player: ProxiedPlayer): Boolean {
        return coordinator.allowAuthenticationCommand(player.uniqueId)
    }

    fun requestCaptcha(player: ProxiedPlayer) {
        coordinator.requestCaptcha(player.uniqueId)
    }

    fun close() {
        pending.keys.forEach { cancel(it) }
        coordinator.close()
    }

    private fun scheduleWhenLoaded(player: ProxiedPlayer) {
        cancel(player.uniqueId)
        coordinator.clear(player.uniqueId)
        val deadline = System.currentTimeMillis() + 30_000L
        val task = plugin.proxy.scheduler.schedule(plugin, {
            if (!player.isConnected) {
                cancel(player.uniqueId)
                return@schedule
            }
            val status = auth.status(player.uniqueId)
            val server = player.server
            plugin.logger.info("[pnAuth-DEBUG] scheduleWhenLoaded tick: player=${player.name}, status=$status, server=${server?.info?.name}, configuredAuthServer=${settings.authServer}")
            if (status == AuthStatus.NOT_LOADED || server == null
                || !server.info.name.equals(settings.authServer, ignoreCase = true)
            ) {
                if (System.currentTimeMillis() >= deadline) {
                    cancel(player.uniqueId)
                    coordinator.clear(player.uniqueId)
                }
                return@schedule
            }
            cancel(player.uniqueId)
            if (status == AuthStatus.AUTHENTICATED) return@schedule
            val protocol = player.pendingConnection.version
            val passwordStage = status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
            try {
                plugin.logger.info("[pnAuth-DEBUG] Calling coordinator.show for ${player.name}, status=$status, protocol=$protocol")
                val shown = coordinator.show(player.uniqueId, status, protocol)
                plugin.logger.info("[pnAuth-DEBUG] coordinator.show result for ${player.name}: $shown")
                if (!shown) {
                    sendCommandFallback(player, status, protocol, passwordStage)
                }
            } catch (exception: RuntimeException) {
                plugin.logger.warning(
                    "Could not show authentication UI for ${player.name}; falling back to commands: ${exception.message}"
                )
                exception.printStackTrace()
                sendCommandFallback(player, status, protocol, passwordStage)
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
        pending[player.uniqueId] = task
    }

    private fun cancel(playerId: UUID) {
        val task = pending.remove(playerId)
        task?.cancel()
    }

    private fun sendCommandFallback(
        player: ProxiedPlayer,
        status: AuthStatus,
        protocol: Int,
        passwordStage: Boolean
    ) {
        if (!passwordStage || auth.shouldUseCommandFallback(player.uniqueId, protocol, true)) {
            player.sendMessage(*BungeeMessages.components(messages.prompt(status), messages.format))
        }
    }
}
