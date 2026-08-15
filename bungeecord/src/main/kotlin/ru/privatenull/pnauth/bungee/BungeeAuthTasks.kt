package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.Title
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import net.md_5.bungee.event.EventHandler
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BungeeAuthTasks internal constructor(
    private val plugin: Plugin,
    private val auth: AuthApi,
    private val messages: AuthMessages,
    private val settings: FeatureSettings,
    private val proxySettings: ProxySettings
) : Listener, AutoCloseable {

    private val tasks: MutableMap<UUID, TaskPair> = ConcurrentHashMap()

    @EventHandler
    fun onServerConnected(event: ServerConnectedEvent) {
        if (!event.server.info.name.equals(proxySettings.authServer, ignoreCase = true)) return
        val player = event.player
        cancel(player.uniqueId)
        var reminder: ScheduledTask? = null
        val reminderSeconds = settings.reminderInterval.seconds
        if (reminderSeconds > 0) {
            reminder = plugin.proxy.scheduler.schedule(plugin, {
                val status = auth.status(player.uniqueId)
                if (!player.isConnected || status == AuthStatus.AUTHENTICATED) {
                    cancel(player.uniqueId)
                    return@schedule
                }
                if (shouldSuppressCommandReminder(player, status)) return@schedule
                player.sendMessage(
                    BungeeMessages.component(
                        messages.text(if (status == AuthStatus.UNREGISTERED) "reminder.register" else "reminder.login"),
                        messages.format()
                    )
                )
                if (settings.titleEnabled) {
                    val title: Title = plugin.proxy.createTitle()
                        .title(BungeeMessages.component(messages.text("display.title"), messages.format()))
                        .subTitle(BungeeMessages.component(messages.text("display.subtitle"), messages.format()))
                        .fadeIn(0).stay(20).fadeOut(5)
                    player.sendTitle(title)
                }
            }, reminderSeconds, reminderSeconds, TimeUnit.SECONDS)
        }
        val timeout = plugin.proxy.scheduler.schedule(plugin, {
            if (player.isConnected && !auth.isAuthenticated(player.uniqueId)) {
                player.disconnect(BungeeMessages.component(messages.text("kick.timeout"), messages.format()))
            }
            cancel(player.uniqueId)
        }, settings.authTimeout.seconds, TimeUnit.SECONDS)
        tasks[player.uniqueId] = TaskPair(reminder, timeout)
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        cancel(event.player.uniqueId)
    }

    override fun close() {
        tasks.keys.forEach { cancel(it) }
    }

    private fun cancel(uniqueId: UUID) {
        val pair = tasks.remove(uniqueId)
        if (pair != null) {
            pair.reminder?.cancel()
            pair.timeout.cancel()
        }
    }

    private fun shouldSuppressCommandReminder(player: ProxiedPlayer, status: AuthStatus): Boolean {
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false
        val protocol = player.pendingConnection.version
        if (auth.shouldUseDialog(player.uniqueId, protocol, true)) return true
        return !auth.shouldUseCommandFallback(player.uniqueId, protocol, true)
    }

    private data class TaskPair(val reminder: ScheduledTask?, val timeout: ScheduledTask)
}
