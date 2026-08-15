package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import net.kyori.adventure.title.Title
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class VelocityAuthTasks(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val auth: AuthApi,
    private val messages: AuthMessages,
    private val settings: FeatureSettings,
    private val proxySettings: ProxySettings,
    private val messageFormat: MessageFormat,
    private val dialogs: VelocityDialogCoordinator?
) : AutoCloseable {

    private val tasks: MutableMap<UUID, TaskPair> = ConcurrentHashMap()

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (!event.player.currentServer.map { server -> server.serverInfo.name }
                .orElse("").equals(proxySettings.authServer, ignoreCase = true)) return
        val player = event.player
        cancel(player.uniqueId)
        var reminder: ScheduledTask? = null
        val reminderSeconds = settings.reminderInterval.toSeconds()
        if (reminderSeconds > 0) {
            reminder = proxy.scheduler.buildTask(plugin, Runnable {
                val status = auth.status(player.uniqueId)
                if (proxy.getPlayer(player.uniqueId).isEmpty || status == AuthStatus.AUTHENTICATED) {
                    cancel(player.uniqueId)
                    return@Runnable
                }
                if (shouldSuppressCommandReminder(player, status)) return@Runnable
                player.sendMessage(
                    VelocityMessages.component(
                        messages.text(if (status == AuthStatus.UNREGISTERED) "reminder.register" else "reminder.login"),
                        messageFormat
                    )
                )
                if (settings.actionBarEnabled) {
                    player.sendActionBar(VelocityMessages.component(messages.text("display.actionbar"), messageFormat))
                }
                if (settings.titleEnabled) {
                    player.showTitle(
                        Title.title(
                            VelocityMessages.component(messages.text("display.title"), messageFormat),
                            VelocityMessages.component(messages.text("display.subtitle"), messageFormat)
                        )
                    )
                }
            }).delay(reminderSeconds, TimeUnit.SECONDS)
                .repeat(reminderSeconds, TimeUnit.SECONDS)
                .schedule()
        }
        val timeout = proxy.scheduler.buildTask(plugin, Runnable {
            if (proxy.getPlayer(player.uniqueId).isPresent && !auth.isAuthenticated(player.uniqueId)) {
                player.disconnect(VelocityMessages.component(messages.text("kick.timeout"), messageFormat))
            }
            cancel(player.uniqueId)
        }).delay(settings.authTimeout.toSeconds(), TimeUnit.SECONDS).schedule()

        tasks[player.uniqueId] = TaskPair(reminder, timeout)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        cancel(event.player.uniqueId)
    }

    override fun close() {
        tasks.keys.toList().forEach(::cancel)
    }

    private fun cancel(uniqueId: UUID) {
        val pair = tasks.remove(uniqueId)
        if (pair != null) {
            pair.reminder?.cancel()
            pair.timeout.cancel()
        }
    }

    private fun shouldSuppressCommandReminder(player: Player, status: AuthStatus): Boolean {
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false
        val protocol = player.protocolVersion.protocol
        val platformSupportsDialogs = dialogs != null && dialogs.available()
        if (auth.shouldUseDialog(player.uniqueId, protocol, platformSupportsDialogs)) return true
        return !auth.shouldUseCommandFallback(player.uniqueId, protocol, platformSupportsDialogs)
    }

    private data class TaskPair(val reminder: ScheduledTask?, val timeout: ScheduledTask)
}
