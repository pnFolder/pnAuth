package ru.privatenull.pnauth.platform

import java.time.Duration

/** Platform-independent scheduler. Player-bound tasks are safe to use on Folia. */
interface PlatformScheduler {
    fun execute(task: Runnable): TaskHandle
    fun execute(player: PnPlayer, task: Runnable): TaskHandle
    fun delayed(delay: Duration, task: Runnable): TaskHandle
    fun delayed(player: PnPlayer, delay: Duration, task: Runnable): TaskHandle
    fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle
    fun repeating(player: PnPlayer, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle
}
