@file:Suppress("DEPRECATION")
package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import net.md_5.bungee.chat.ComponentSerializer
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.platform.DefaultTaskRegistry
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.PlatformScheduler
import ru.privatenull.pnauth.platform.PlatformType
import ru.privatenull.pnauth.platform.Player
import ru.privatenull.pnauth.platform.TaskHandle
import ru.privatenull.pnauth.platform.TaskRegistry
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** BungeeCord adapter for the public pnAuth platform API. */
class BungeePlatform(
    plugin: Plugin,
    private val display: PlayerDisplay,
    private val messageFormat: MessageFormat,
    private val dialogs: PlayerDialogs
) : Platform {

    private val proxy: ProxyServer = plugin.proxy
    private val scheduler: PlatformScheduler = BungeeScheduler(plugin)
    private val tasks: TaskRegistry = DefaultTaskRegistry(scheduler)

    override fun type(): PlatformType = PlatformType.BUNGEECORD
    override fun player(uniqueId: UUID): Optional<Player> = wrap(proxy.getPlayer(uniqueId))
    override fun player(username: String): Optional<Player> = wrap(proxy.getPlayer(username))
    override fun scheduler(): PlatformScheduler = scheduler
    override fun tasks(): TaskRegistry = tasks
    override fun dialogs(): PlayerDialogs = dialogs

    override fun players(): Collection<Player> {
        return proxy.players.map { BungeePlayer(it) }
    }

    private fun wrap(player: ProxiedPlayer?): Optional<Player> {
        return Optional.ofNullable(player).map { BungeePlayer(it) }
    }

    private inner class BungeePlayer(private val delegate: ProxiedPlayer) : Player {
        override fun uniqueId(): UUID = delegate.uniqueId
        override fun username(): String = delegate.name
        override fun remoteAddress(): InetSocketAddress = delegate.address ?: InetSocketAddress("127.0.0.1", 0)
        override fun connected(): Boolean = delegate.isConnected
        override fun hasPermission(permission: String): Boolean = delegate.hasPermission(permission)
        override fun display(): PlayerDisplay = display
        override fun dialogs(): PlayerDialogs = dialogs
        override fun scheduler(): PlatformScheduler = scheduler

        override fun currentServer(): Optional<String> {
            return Optional.ofNullable(delegate.server).map { it.info.name }
        }

        override fun sendMessage(message: String) {
            delegate.sendMessage(BungeeMessages.component(message, messageFormat))
        }

        override fun sendMessage(message: net.kyori.adventure.text.Component) {
            val json = com.github.retrooper.packetevents.util.adventure.AdventureSerializer.toJson(message)
            delegate.sendMessage(*ComponentSerializer.parse(json))
        }

        override fun sendMessages(messages: Iterable<net.kyori.adventure.text.Component>) {
            messages.forEach { sendMessage(it) }
        }

        override fun disconnect(reason: String) {
            delegate.disconnect(BungeeMessages.component(reason, messageFormat))
        }
    }

    private class BungeeScheduler(private val plugin: Plugin) : PlatformScheduler {
        override fun execute(task: Runnable): TaskHandle = delayed(Duration.ZERO, task)
        override fun execute(player: Player, task: Runnable): TaskHandle = execute(task)
        override fun delayed(player: Player, delay: Duration, task: Runnable): TaskHandle = delayed(delay, task)
        override fun repeating(player: Player, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            return repeating(initialDelay, interval, task)
        }

        override fun delayed(delay: Duration, task: Runnable): TaskHandle {
            val scheduled = plugin.proxy.scheduler.schedule(
                plugin, task, milliseconds(delay), TimeUnit.MILLISECONDS
            )
            return ScheduledHandle(scheduled)
        }

        override fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            val scheduled = plugin.proxy.scheduler.schedule(
                plugin, task, milliseconds(initialDelay), Math.max(1L, milliseconds(interval)),
                TimeUnit.MILLISECONDS
            )
            return ScheduledHandle(scheduled)
        }

        companion object {
            private fun milliseconds(duration: Duration?): Long {
                return if (duration == null) 0L else Math.max(0L, duration.toMillis())
            }
        }
    }

    private class ScheduledHandle(private val task: ScheduledTask) : TaskHandle {
        private val isCancelled = AtomicBoolean()

        override fun cancelled(): Boolean = isCancelled.get()
        override fun cancel() {
            if (isCancelled.compareAndSet(false, true)) task.cancel()
        }
    }
}
