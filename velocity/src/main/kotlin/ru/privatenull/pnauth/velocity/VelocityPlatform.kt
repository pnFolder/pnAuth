package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.Player as VPlayer
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

class VelocityPlatform(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val display: PlayerDisplay,
    private val messageFormat: MessageFormat,
    private val dialogs: PlayerDialogs
) : Platform {

    private val scheduler: PlatformScheduler = VelocityScheduler(plugin, proxy)
    private val tasks: TaskRegistry = DefaultTaskRegistry(scheduler)

    override fun type(): PlatformType = PlatformType.VELOCITY
    override fun player(uniqueId: UUID): Optional<Player> = wrap(proxy.getPlayer(uniqueId))
    override fun player(username: String): Optional<Player> = wrap(proxy.getPlayer(username))
    override fun scheduler(): PlatformScheduler = scheduler
    override fun tasks(): TaskRegistry = tasks
    override fun dialogs(): PlayerDialogs = dialogs

    override fun players(): Collection<Player> {
        return proxy.allPlayers.map { PPlayer(it) }
    }

    private fun wrap(player: Optional<VPlayer>): Optional<Player> {
        return player.map { PPlayer(it) }
    }

    private inner class PPlayer(private val delegate: VPlayer) : Player {
        override fun uniqueId(): UUID = delegate.uniqueId
        override fun username(): String = delegate.username
        override fun remoteAddress(): InetSocketAddress = delegate.remoteAddress
        override fun connected(): Boolean = delegate.isActive
        override fun hasPermission(permission: String): Boolean = delegate.hasPermission(permission)
        override fun display(): PlayerDisplay = display
        override fun dialogs(): PlayerDialogs = dialogs
        override fun scheduler(): PlatformScheduler = scheduler

        override fun currentServer(): Optional<String> {
            return delegate.currentServer.map { it.serverInfo.name }
        }

        override fun sendMessage(message: String) {
            delegate.sendMessage(VelocityMessages.component(message, messageFormat))
        }

        override fun sendMessage(message: net.kyori.adventure.text.Component) {
            delegate.sendMessage(message)
        }

        override fun sendMessages(messages: Iterable<net.kyori.adventure.text.Component>) {
            messages.forEach { delegate.sendMessage(it) }
        }

        override fun disconnect(reason: String) {
            delegate.disconnect(VelocityMessages.component(reason, messageFormat))
        }
    }

    private class VelocityScheduler(
        private val plugin: Any,
        private val proxy: ProxyServer
    ) : PlatformScheduler {
        override fun execute(task: Runnable): TaskHandle = delayed(Duration.ZERO, task)
        override fun execute(player: Player, task: Runnable): TaskHandle = execute(task)
        override fun delayed(player: Player, delay: Duration, task: Runnable): TaskHandle = delayed(delay, task)
        override fun repeating(player: Player, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            return repeating(initialDelay, interval, task)
        }

        override fun delayed(delay: Duration, task: Runnable): TaskHandle {
            val scheduled = proxy.scheduler.buildTask(plugin, task)
                .delay(milliseconds(delay), TimeUnit.MILLISECONDS)
                .schedule()
            return ScheduledHandle(scheduled)
        }

        override fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            val scheduled = proxy.scheduler.buildTask(plugin, task)
                .delay(milliseconds(initialDelay), TimeUnit.MILLISECONDS)
                .repeat(Math.max(1L, milliseconds(interval)), TimeUnit.MILLISECONDS)
                .schedule()
            return ScheduledHandle(scheduled)
        }

        companion object {
            private fun milliseconds(duration: Duration?): Long {
                return if (duration == null) 0L else Math.max(0L, duration.toMillis())
            }
        }
    }

    private class ScheduledHandle(private val task: com.velocitypowered.api.scheduler.ScheduledTask) : TaskHandle {
        private val isCancelled = AtomicBoolean()

        override fun cancelled(): Boolean = isCancelled.get()
        override fun cancel() {
            if (isCancelled.compareAndSet(false, true)) task.cancel()
        }
    }
}
