package ru.privatenull.pnauth.paper

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.platform.DefaultTaskRegistry
import ru.privatenull.pnauth.platform.PlatformScheduler
import ru.privatenull.pnauth.platform.PlatformType
import ru.privatenull.pnauth.platform.PnPlatform
import ru.privatenull.pnauth.platform.PnPlayer
import ru.privatenull.pnauth.platform.TaskHandle
import ru.privatenull.pnauth.platform.TaskRegistry
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Paper and Folia adapter for the public pnAuth platform API. */
class PaperPlatform(
    private val plugin: Plugin,
    private val display: PlayerDisplay,
    private val dialogs: PlayerDialogs
) : PnPlatform {

    private val platformScheduler: PlatformScheduler = Scheduler()
    private val taskRegistry: TaskRegistry = DefaultTaskRegistry(platformScheduler)
    private val platformType: PlatformType = if (Bukkit.getName().lowercase().contains("folia")) {
        PlatformType.FOLIA
    } else {
        PlatformType.PAPER
    }

    override fun type(): PlatformType = platformType
    override fun player(uniqueId: UUID): Optional<PnPlayer> = wrap(Bukkit.getPlayer(uniqueId))
    override fun player(username: String): Optional<PnPlayer> = wrap(Bukkit.getPlayerExact(username))
    override fun scheduler(): PlatformScheduler = platformScheduler
    override fun tasks(): TaskRegistry = taskRegistry
    override fun dialogs(): PlayerDialogs = dialogs

    override fun players(): Collection<PnPlayer> {
        return Bukkit.getOnlinePlayers().map { Wrapper(it) }
    }

    private fun wrap(player: Player?): Optional<PnPlayer> {
        return Optional.ofNullable(player).map { Wrapper(it) }
    }

    private inner class Wrapper(private val delegate: Player) : PnPlayer {
        override fun uniqueId(): UUID = delegate.uniqueId
        override fun username(): String = delegate.name
        override fun remoteAddress(): InetSocketAddress = delegate.address ?: InetSocketAddress("127.0.0.1", 0)
        override fun currentServer(): Optional<String> = Optional.of(Bukkit.getServer().name)
        override fun connected(): Boolean = delegate.isConnected
        override fun hasPermission(permission: String): Boolean = delegate.hasPermission(permission)
        override fun display(): PlayerDisplay = display
        override fun dialogs(): PlayerDialogs = dialogs
        override fun scheduler(): PlatformScheduler = platformScheduler
        override fun sendMessage(message: String) { delegate.sendMessage(message) }
        override fun sendMessage(message: net.kyori.adventure.text.Component) { delegate.sendMessage(message) }
        override fun sendMessages(messages: Iterable<net.kyori.adventure.text.Component>) {
            messages.forEach { delegate.sendMessage(it) }
        }
        override fun disconnect(reason: String) {
            delegate.kick(net.kyori.adventure.text.Component.text(reason))
        }
    }

    private inner class Scheduler : PlatformScheduler {
        override fun execute(task: Runnable): TaskHandle = delayed(Duration.ZERO, task)
        override fun execute(player: PnPlayer, task: Runnable): TaskHandle = delayed(player, Duration.ZERO, task)

        override fun delayed(delay: Duration, task: Runnable): TaskHandle {
            val scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(
                plugin, { task.run() }, ticks(delay)
            )
            return handle { scheduled.cancel() }
        }

        override fun delayed(player: PnPlayer, delay: Duration, task: Runnable): TaskHandle {
            val delegate = Bukkit.getPlayer(player.uniqueId()) ?: return handle {}
            val scheduled = delegate.scheduler.runDelayed(
                plugin, { task.run() }, null, ticks(delay)
            )
            return handle { scheduled?.cancel() }
        }

        override fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            val scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, { task.run() }, ticks(initialDelay), ticks(interval)
            )
            return handle { scheduled.cancel() }
        }

        override fun repeating(player: PnPlayer, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle {
            val delegate = Bukkit.getPlayer(player.uniqueId()) ?: return handle {}
            val scheduled = delegate.scheduler.runAtFixedRate(
                plugin, { task.run() }, null, ticks(initialDelay), ticks(interval)
            )
            return handle { scheduled?.cancel() }
        }

        private fun ticks(duration: Duration?): Long {
            val millis = if (duration == null) 0L else Math.max(0L, duration.toMillis())
            return Math.max(1L, (millis + 49L) / 50L)
        }

        private fun handle(cancellation: Runnable): TaskHandle {
            return object : TaskHandle {
                private val isCancelled = AtomicBoolean()
                override fun cancelled(): Boolean = isCancelled.get()
                override fun cancel() {
                    if (isCancelled.compareAndSet(false, true)) cancellation.run()
                }
            }
        }
    }
}
