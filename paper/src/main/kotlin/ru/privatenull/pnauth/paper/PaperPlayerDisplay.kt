package ru.privatenull.pnauth.paper

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.privatenull.pnauth.display.ActionBarHandle
import ru.privatenull.pnauth.display.ActionBarOptions
import ru.privatenull.pnauth.display.BossBarColor
import ru.privatenull.pnauth.display.BossBarHandle
import ru.privatenull.pnauth.display.BossBarOptions
import ru.privatenull.pnauth.display.BossBarOverlay
import ru.privatenull.pnauth.display.DisplayHandle
import ru.privatenull.pnauth.display.Easing
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.display.TitleBuilder
import ru.privatenull.pnauth.display.TitleHandle
import ru.privatenull.pnauth.display.TitleOptions
import ru.privatenull.pnauth.platform.PlayerResourceKey
import ru.privatenull.pnauth.platform.adapter.PlatformDisplayAdapter
import ru.privatenull.pnauth.message.MessageComponents
import ru.privatenull.pnauth.message.MessageFormat
import java.time.Duration
import java.util.HashSet
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Stateful Paper/Folia display implementation backed by Adventure audiences. */
class PaperPlayerDisplay(
    private val plugin: Plugin,
    private val messageFormat: MessageFormat = MessageFormat.MINI_MESSAGE
) : PlatformDisplayAdapter, AutoCloseable {

    override fun showTitle(uniqueId: UUID, builder: TitleBuilder) {
        title(
            uniqueId,
            PLATFORM_TITLE_ID,
            TitleOptions(
                builder.title,
                builder.subtitle,
                builder.fadeIn,
                builder.stay,
                builder.fadeOut,
                Duration.ZERO,
                builder.fadeIn.plus(builder.stay).plus(builder.fadeOut)
            )
        )
    }

    override fun clearTitle(uniqueId: UUID) {
        removeTitle(uniqueId, PLATFORM_TITLE_ID)
    }

    private val actionBars: MutableMap<PlayerResourceKey, Action> = ConcurrentHashMap()
    private val titles: MutableMap<PlayerResourceKey, PlayerTitle> = ConcurrentHashMap()
    private val bossBars: MutableMap<PlayerResourceKey, PlayerBossBar> = ConcurrentHashMap()

    override fun actionBar(playerId: UUID, displayId: String, options: ActionBarOptions): ActionBarHandle {
        val key = PlayerResourceKey(playerId, displayId)
        return actionBars.compute(key) { _, current ->
            if (current == null || !current.active()) Action(key, options)
            else {
                current.applyOptions(options)
                current
            }
        }!!
    }

    override fun title(playerId: UUID, displayId: String, options: TitleOptions): TitleHandle {
        val key = PlayerResourceKey(playerId, displayId)
        return titles.compute(key) { _, current ->
            if (current == null || !current.active()) PlayerTitle(key, options)
            else {
                current.applyOptions(options)
                current
            }
        }!!
    }

    override fun bossBar(playerId: UUID, displayId: String, options: BossBarOptions): BossBarHandle {
        val key = PlayerResourceKey(playerId, displayId)
        return bossBars.compute(key) { _, current ->
            if (current == null || !current.active()) PlayerBossBar(key, options)
            else {
                current.applyOptions(options)
                current
            }
        }!!
    }

    override fun findActionBar(playerId: UUID, displayId: String): Optional<ActionBarHandle> =
        Optional.ofNullable(actionBars[PlayerResourceKey(playerId, displayId)])

    override fun findTitle(playerId: UUID, displayId: String): Optional<TitleHandle> =
        Optional.ofNullable(titles[PlayerResourceKey(playerId, displayId)])

    override fun findBossBar(playerId: UUID, displayId: String): Optional<BossBarHandle> =
        Optional.ofNullable(bossBars[PlayerResourceKey(playerId, displayId)])

    override fun removeActionBar(playerId: UUID, displayId: String): Boolean =
        close(actionBars.remove(PlayerResourceKey(playerId, displayId)))

    override fun removeTitle(playerId: UUID, displayId: String): Boolean =
        close(titles.remove(PlayerResourceKey(playerId, displayId)))

    override fun removeBossBar(playerId: UUID, displayId: String): Boolean =
        close(bossBars.remove(PlayerResourceKey(playerId, displayId)))

    override fun clear(playerId: UUID) {
        actionBars.entries.removeIf { closeIfPlayer(it, playerId) }
        titles.entries.removeIf { closeIfPlayer(it, playerId) }
        bossBars.entries.removeIf { closeIfPlayer(it, playerId) }
    }

    override fun close() {
        actionBars.values.forEach { it.close() }
        titles.values.forEach { it.close() }
        bossBars.values.forEach { it.close() }
        actionBars.clear()
        titles.clear()
        bossBars.clear()
    }

    private abstract inner class Base(
        protected val key: PlayerResourceKey,
        lifetime: Duration?
    ) : DisplayHandle {
        @Volatile protected var isCompletedActive: Boolean = true
        @Volatile protected var isCompletedPaused: Boolean = false
        private @Volatile var expiry: ScheduledTask? = null

        init {
            lifetime(lifetime ?: Duration.ZERO)
        }

        override fun playerId(): UUID = key.playerId
        override fun displayId(): String = key.name
        override fun active(): Boolean = isCompletedActive
        override fun paused(): Boolean = isCompletedPaused

        override fun pause() {
            isCompletedPaused = true
        }

        override fun resume() {
            isCompletedPaused = false
        }

        override fun lifetime(lifetime: Duration) {
            cancel(expiry)
            if (valid(lifetime).isZero) return
            expiry = schedule(lifetime) { close() }
        }

        protected fun withPlayer(action: (Player) -> Unit) {
            val player = Bukkit.getPlayer(key.playerId) ?: return
            player.scheduler.run(plugin, { action(player) }, null)
        }

        protected fun schedule(delay: Duration?, action: Runnable): ScheduledTask? {
            val player = Bukkit.getPlayer(key.playerId) ?: return null
            return player.scheduler.runDelayed(
                plugin, { action.run() }, null, ticks(delay)
            )
        }

        protected fun repeat(initialDelay: Duration?, interval: Duration?, action: Runnable): ScheduledTask? {
            val player = Bukkit.getPlayer(key.playerId) ?: return null
            return player.scheduler.runAtFixedRate(
                plugin, { action.run() }, null, ticks(initialDelay), ticks(interval)
            )
        }

        protected fun finish() {
            cancel(expiry)
        }
    }

    private inner class Action(
        key: PlayerResourceKey,
        options: ActionBarOptions
    ) : Base(key, options.lifetime()), ActionBarHandle {
        @Volatile private var actionText: String = ""
        @Volatile private var refresh: ScheduledTask? = null

        init {
            applyOptions(options)
        }

        fun applyOptions(options: ActionBarOptions) {
            text(options.text())
            refreshInterval(options.refreshInterval())
            lifetime(options.lifetime())
        }

        override fun text(text: String) {
            this.actionText = safe(text)
            sendNow()
        }

        override fun refreshInterval(interval: Duration) {
            cancel(refresh)
            if (!valid(interval).isZero) refresh = repeat(interval, interval) { sendNow() }
        }

        override fun sendNow() {
            if (isCompletedActive && !isCompletedPaused) withPlayer { player -> player.sendActionBar(component(actionText)) }
        }

        override fun close() {
            if (!isCompletedActive) return
            isCompletedActive = false
            actionBars.remove(key, this)
            cancel(refresh)
            finish()
            withPlayer { player -> player.sendActionBar(Component.empty()) }
        }
    }

    private inner class PlayerTitle(
        key: PlayerResourceKey,
        options: TitleOptions
    ) : Base(key, options.lifetime()), TitleHandle {
        @Volatile private var titleText = ""
        @Volatile private var subtitleText = ""
        @Volatile private var fadeIn = Duration.ZERO
        @Volatile private var stay = Duration.ofSeconds(2)
        @Volatile private var fadeOut = Duration.ZERO
        @Volatile private var refresh: ScheduledTask? = null

        init {
            applyOptions(options)
        }

        fun applyOptions(options: TitleOptions) {
            titleText = safe(options.title())
            subtitleText = safe(options.subtitle())
            timings(options.fadeIn(), options.stay(), options.fadeOut())
            repeatInterval(options.repeatInterval())
            lifetime(options.lifetime())
            showNow()
        }

        override fun title(title: String) {
            titleText = safe(title)
            showNow()
        }

        override fun subtitle(subtitle: String) {
            subtitleText = safe(subtitle)
            showNow()
        }

        override fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration) {
            this.fadeIn = valid(fadeIn)
            this.stay = valid(stay)
            this.fadeOut = valid(fadeOut)
        }

        override fun repeatInterval(interval: Duration) {
            cancel(refresh)
            if (!valid(interval).isZero) refresh = repeat(interval, interval) { showNow() }
        }

        override fun showNow() {
            if (isCompletedActive && !isCompletedPaused) {
                withPlayer { player ->
                    player.showTitle(
                        Title.title(
                            component(titleText),
                            component(subtitleText),
                            Title.Times.times(fadeIn, stay, fadeOut)
                        )
                    )
                }
            }
        }

        override fun clear() {
            withPlayer { it.clearTitle() }
        }

        override fun release() {
            if (!isCompletedActive) return
            isCompletedActive = false
            titles.remove(key, this)
            cancel(refresh)
            finish()
        }

        override fun close() {
            if (!isCompletedActive) return
            release()
            clear()
        }
    }

    private inner class PlayerBossBar(
        key: PlayerResourceKey,
        options: BossBarOptions
    ) : Base(key, options.lifetime()), BossBarHandle {
        private val bar: BossBar
        @Volatile private var currentProgress: Float = clamp(options.progress())
        @Volatile private var animation: ScheduledTask? = null

        init {
            bar = BossBar.bossBar(
                component(options.text()),
                currentProgress,
                adventureColor(options.color()),
                adventureOverlay(options.overlay())
            )
            properties(options.darkenScreen(), options.playBossMusic(), options.createWorldFog())
            lifetime(options.lifetime())
            withPlayer { player -> player.showBossBar(bar) }
        }

        fun applyOptions(options: BossBarOptions) {
            text(options.text())
            progress(options.progress())
            color(options.color())
            overlay(options.overlay())
            properties(options.darkenScreen(), options.playBossMusic(), options.createWorldFog())
        }

        override fun text(text: String) {
            bar.name(component(safe(text)))
        }

        override fun progress(progress: Float) {
            currentProgress = clamp(progress)
            bar.progress(currentProgress)
        }

        override fun color(color: BossBarColor) {
            bar.color(adventureColor(color))
        }

        override fun overlay(overlay: BossBarOverlay) {
            bar.overlay(adventureOverlay(overlay))
        }

        override fun animateProgress(target: Float, duration: Duration, easing: Easing) {
            cancel(animation)
            val start = currentProgress
            val end = clamp(target)
            val steps = Math.max(1, (valid(duration).toMillis() / 50L).toInt())
            val step = AtomicInteger()
            val curve = easing
            animation = repeat(Duration.ofMillis(50), Duration.ofMillis(50)) {
                if (isCompletedPaused) return@repeat
                val ratio = Math.min(1f, step.incrementAndGet() / steps.toFloat())
                progress(start + (end - start) * curve.apply(ratio))
                if (ratio >= 1f) cancel(animation)
            }
        }

        override fun properties(darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean) {
            val flags: MutableSet<BossBar.Flag> = HashSet()
            if (darkenScreen) flags.add(BossBar.Flag.DARKEN_SCREEN)
            if (playBossMusic) flags.add(BossBar.Flag.PLAY_BOSS_MUSIC)
            if (createWorldFog) flags.add(BossBar.Flag.CREATE_WORLD_FOG)
            bar.flags(flags)
        }

        override fun pause() {
            super.pause()
            withPlayer { player -> player.hideBossBar(bar) }
        }

        override fun resume() {
            super.resume()
            withPlayer { player -> player.showBossBar(bar) }
        }

        override fun close() {
            if (!isCompletedActive) return
            isCompletedActive = false
            bossBars.remove(key, this)
            cancel(animation)
            finish()
            withPlayer { player -> player.hideBossBar(bar) }
        }
    }

    companion object {
        private const val PLATFORM_TITLE_ID = "pnauth:platform-title"

        private fun close(handle: DisplayHandle?): Boolean {
            if (handle == null) return false
            handle.close()
            return true
        }

        private fun closeIfPlayer(entry: Map.Entry<PlayerResourceKey, DisplayHandle>, playerId: UUID): Boolean {
            if (entry.key.playerId != playerId) return false
            entry.value.close()
            return true
        }

        private fun safe(value: String?): String = value ?: ""
        private fun valid(value: Duration?): Duration = if (value == null || value.isNegative) Duration.ZERO else value
        private fun ticks(value: Duration?): Long = Math.max(1L, (valid(value).toMillis() + 49L) / 50L)
        private fun cancel(task: ScheduledTask?) { task?.cancel() }
        private fun clamp(value: Float): Float = Math.max(0f, Math.min(1f, value))
        private fun adventureColor(value: BossBarColor?): BossBar.Color =
            BossBar.Color.valueOf((value ?: BossBarColor.PURPLE).name)
        private fun adventureOverlay(value: BossBarOverlay?): BossBar.Overlay =
            BossBar.Overlay.valueOf((value ?: BossBarOverlay.PROGRESS).name)
    }

    private fun component(value: String?): Component = MessageComponents.deserialize(value, messageFormat)
}
