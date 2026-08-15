package ru.privatenull.pnauth.platform

import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.dialog.UnsupportedPlayerDialogs
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** Empty platform used until the hosting adapter has completed initialization. */
class UnavailablePlatform : PnPlatform {
    private val dialogs: PlayerDialogs = UnsupportedPlayerDialogs()
    private val scheduler: PlatformScheduler = ImmediateScheduler()
    private val tasks: TaskRegistry = DefaultTaskRegistry(scheduler)

    override fun type(): PlatformType = PlatformType.PAPER
    override fun player(uniqueId: UUID): Optional<PnPlayer> = Optional.empty()
    override fun player(username: String): Optional<PnPlayer> = Optional.empty()
    override fun players(): Collection<PnPlayer> = emptyList<PnPlayer>()
    override fun scheduler(): PlatformScheduler = scheduler
    override fun tasks(): TaskRegistry = tasks
    override fun dialogs(): PlayerDialogs = dialogs

    private class ImmediateScheduler : PlatformScheduler {
        override fun execute(task: Runnable): TaskHandle {
            task.run()
            return DoneTask.INSTANCE
        }
        override fun execute(player: PnPlayer, task: Runnable): TaskHandle = execute(task)
        override fun delayed(delay: Duration, task: Runnable): TaskHandle = DoneTask.INSTANCE
        override fun delayed(player: PnPlayer, delay: Duration, task: Runnable): TaskHandle = DoneTask.INSTANCE
        override fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle = DoneTask.INSTANCE
        override fun repeating(player: PnPlayer, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle = DoneTask.INSTANCE
    }

    private enum class DoneTask : TaskHandle {
        INSTANCE;
        override fun cancelled(): Boolean = true
        override fun cancel() {}
    }
}
