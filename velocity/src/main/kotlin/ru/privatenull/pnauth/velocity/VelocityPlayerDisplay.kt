package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import ru.privatenull.pnauth.display.ActionBarHandle
import ru.privatenull.pnauth.display.ActionBarOptions
import ru.privatenull.pnauth.display.BossBarColor
import ru.privatenull.pnauth.display.BossBarHandle
import ru.privatenull.pnauth.display.BossBarOptions
import ru.privatenull.pnauth.display.BossBarOverlay
import ru.privatenull.pnauth.display.DisplayHandle
import ru.privatenull.pnauth.display.Easing
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.display.TitleHandle
import ru.privatenull.pnauth.display.TitleOptions
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.platform.PlayerResourceKey
import java.time.Duration
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VelocityPlayerDisplay(
    private val proxy: ProxyServer,
    private val format: MessageFormat
) : ru.privatenull.pnauth.platform.adapter.PlatformDisplayAdapter, AutoCloseable {

    private val titleAdapter = ru.privatenull.pnauth.velocity.display.VelocityTitleAdapter(proxy, format)

    override fun showTitle(uniqueId: UUID, builder: ru.privatenull.pnauth.display.TitleBuilder) {
        titleAdapter.showTitle(uniqueId, builder)
    }

    override fun clearTitle(uniqueId: UUID) {
        titleAdapter.clearTitle(uniqueId)
    }

    private val actions = ConcurrentHashMap<PlayerResourceKey, Action>()
    private val titles = ConcurrentHashMap<PlayerResourceKey, Titles>()
    private val bosses = ConcurrentHashMap<PlayerResourceKey, Boss>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        val thread = Thread(runnable, "pnauth-display")
        thread.isDaemon = true
        thread
    }

    override fun actionBar(playerId: UUID, displayId: String, options: ActionBarOptions): ActionBarHandle {
        val key = PlayerResourceKey(playerId, displayId)
        val handle = actions.computeIfAbsent(key) { Action(key, options) }
        handle.applyOptions(options)
        return handle
    }

    override fun title(playerId: UUID, displayId: String, options: TitleOptions): TitleHandle {
        val key = PlayerResourceKey(playerId, displayId)
        val handle = titles.computeIfAbsent(key) { Titles(key, options) }
        handle.applyOptions(options)
        return handle
    }

    override fun bossBar(playerId: UUID, displayId: String, options: BossBarOptions): BossBarHandle {
        val key = PlayerResourceKey(playerId, displayId)
        val handle = bosses.computeIfAbsent(key) { Boss(key, options) }
        handle.applyOptions(options)
        return handle
    }

    override fun findActionBar(playerId: UUID, displayId: String): Optional<ActionBarHandle> {
        return Optional.ofNullable(actions[PlayerResourceKey(playerId, displayId)])
    }

    override fun findTitle(playerId: UUID, displayId: String): Optional<TitleHandle> {
        return Optional.ofNullable(titles[PlayerResourceKey(playerId, displayId)])
    }

    override fun findBossBar(playerId: UUID, displayId: String): Optional<BossBarHandle> {
        return Optional.ofNullable(bosses[PlayerResourceKey(playerId, displayId)])
    }

    override fun removeActionBar(playerId: UUID, displayId: String): Boolean {
        val removed = actions.remove(PlayerResourceKey(playerId, displayId))
        if (removed != null) {
            removed.close()
            return true
        }
        return false
    }

    override fun removeTitle(playerId: UUID, displayId: String): Boolean {
        val removed = titles.remove(PlayerResourceKey(playerId, displayId))
        if (removed != null) {
            removed.close()
            return true
        }
        return false
    }

    override fun removeBossBar(playerId: UUID, displayId: String): Boolean {
        val removed = bosses.remove(PlayerResourceKey(playerId, displayId))
        if (removed != null) {
            removed.close()
            return true
        }
        return false
    }

    override fun clear(playerId: UUID) {
        actions.keys.filter { it.playerId == playerId }.forEach { removeActionBar(it.playerId, it.name) }
        titles.keys.filter { it.playerId == playerId }.forEach { removeTitle(it.playerId, it.name) }
        bosses.keys.filter { it.playerId == playerId }.forEach { removeBossBar(it.playerId, it.name) }
    }

    override fun close() {
        actions.values.forEach(Action::close)
        titles.values.forEach(Titles::close)
        bosses.values.forEach(Boss::close)
        scheduler.shutdownNow()
    }

    private abstract inner class Base(
        val key: PlayerResourceKey,
        lifetime: Duration?
    ) : DisplayHandle {
        val playerId: UUID = key.playerId
        @Volatile protected var isCompletedActive: Boolean = true
        @Volatile protected var isCompletedPaused: Boolean = false
        @Volatile var expiry: ScheduledFuture<*>? = null

        init {
            lifetime(lifetime ?: Duration.ZERO)
        }

        override fun playerId(): UUID = playerId
        override fun displayId(): String = key.name
        override fun active(): Boolean = isCompletedActive
        override fun paused(): Boolean = isCompletedPaused

        override fun pause() {
            isCompletedPaused = true
        }

        override fun resume() {
            if (isCompletedActive) isCompletedPaused = false
        }

        @Synchronized
        override fun lifetime(lifetime: Duration) {
            cancel(expiry)
            val d = valid(lifetime)
            if (!d.isZero) {
                expiry = scheduler.schedule({ close() }, d.toMillis(), TimeUnit.MILLISECONDS)
            }
        }

        fun player(): Player? = proxy.getPlayer(playerId).orElse(null)

        fun cancel(task: ScheduledFuture<*>?) {
            task?.cancel(false)
        }
    }

    private inner class Action(
        key: PlayerResourceKey,
        o: ActionBarOptions
    ) : Base(key, o.lifetime()), ActionBarHandle {

        @Volatile private var valueText: String = o.text()
        @Volatile private var interval: Duration = valid(o.refreshInterval())
        @Volatile private var refresh: ScheduledFuture<*>? = null

        init {
            sendNow()
            reschedule()
        }

        fun applyOptions(o: ActionBarOptions) {
            text(o.text())
            refreshInterval(o.refreshInterval())
            lifetime(o.lifetime())
        }

        override fun text(text: String) {
            valueText = text
            sendNow()
        }

        @Synchronized
        override fun refreshInterval(interval: Duration) {
            this.interval = valid(interval)
            reschedule()
        }

        @Synchronized
        private fun reschedule() {
            cancel(refresh)
            if (isCompletedActive && !interval.isZero) {
                refresh = scheduler.scheduleAtFixedRate(
                    { sendNow() }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS
                )
            }
        }

        override fun sendNow() {
            val p = player()
            if (isCompletedActive && !isCompletedPaused && p != null) {
                p.sendActionBar(VelocityMessages.component(valueText, format))
            }
        }

        @Synchronized
        override fun close() {
            if (!isCompletedActive) return
            isCompletedActive = false
            actions.remove(key, this)
            cancel(refresh)
            cancel(expiry)
            val p = player()
            p?.sendActionBar(Component.empty())
        }
    }

    private inner class Titles(
        key: PlayerResourceKey,
        o: TitleOptions
    ) : Base(key, o.lifetime()), TitleHandle {

        @Volatile private var valueTitle: String = o.title()
        @Volatile private var valueSubtitle: String = o.subtitle()
        @Volatile private var fadeIn: Duration = valid(o.fadeIn())
        @Volatile private var stay: Duration = valid(o.stay())
        @Volatile private var fadeOut: Duration = valid(o.fadeOut())
        @Volatile private var repeat: Duration = valid(o.repeatInterval())
        @Volatile private var refresh: ScheduledFuture<*>? = null

        init {
            showNow()
            reschedule()
        }

        fun applyOptions(o: TitleOptions) {
            valueTitle = o.title()
            valueSubtitle = o.subtitle()
            timings(o.fadeIn(), o.stay(), o.fadeOut())
            repeatInterval(o.repeatInterval())
            lifetime(o.lifetime())
            showNow()
        }

        @Synchronized
        override fun title(title: String) {
            valueTitle = title
            showNow()
        }

        @Synchronized
        override fun subtitle(subtitle: String) {
            valueSubtitle = subtitle
            showNow()
        }

        @Synchronized
        override fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration) {
            this.fadeIn = valid(fadeIn)
            this.stay = valid(stay)
            this.fadeOut = valid(fadeOut)
        }

        @Synchronized
        override fun repeatInterval(interval: Duration) {
            repeat = valid(interval)
            reschedule()
        }

        @Synchronized
        private fun reschedule() {
            cancel(refresh)
            if (isCompletedActive && !repeat.isZero) {
                refresh = scheduler.scheduleAtFixedRate(
                    { showNow() }, repeat.toMillis(), repeat.toMillis(), TimeUnit.MILLISECONDS
                )
            }
        }

        @Synchronized
        override fun showNow() {
            val p = player()
            if (isCompletedActive && !isCompletedPaused && p != null) {
                p.showTitle(
                    Title.title(
                        VelocityMessages.component(valueTitle, format),
                        VelocityMessages.component(valueSubtitle, format),
                        Title.Times.times(fadeIn, stay, fadeOut)
                    )
                )
            }
        }

        override fun clear() {
            player()?.clearTitle()
        }

        @Synchronized
        override fun release() {
            if (!isCompletedActive) return
            isCompletedActive = false
            titles.remove(key, this)
            cancel(refresh)
            cancel(expiry)
        }

        @Synchronized
        override fun close() {
            if (!isCompletedActive) return
            release()
            clear()
        }
    }

    private inner class Boss(
        key: PlayerResourceKey,
        o: BossBarOptions
    ) : Base(key, o.lifetime()), BossBarHandle {

        private val bar: BossBar = BossBar.bossBar(
            VelocityMessages.component(o.text(), format),
            clamp(o.progress()),
            colorOf(o.color()),
            overlayOf(o.overlay())
        )
        @Volatile private var valueText: String = o.text()
        @Volatile private var progress: Float = clamp(o.progress())
        @Volatile private var animation: ScheduledFuture<*>? = null
        @Volatile private var subscribedPlayer: Player? = null

        init {
            properties(o.darkenScreen(), o.playBossMusic(), o.createWorldFog())
            showNow()
        }

        fun applyOptions(o: BossBarOptions) {
            text(o.text())
            progress(o.progress())
            color(o.color())
            overlay(o.overlay())
            properties(o.darkenScreen(), o.playBossMusic(), o.createWorldFog())
            lifetime(o.lifetime())
        }

        override fun text(text: String) {
            valueText = text
            bar.name(VelocityMessages.component(valueText, format))
        }

        override fun progress(progress: Float) {
            this.progress = clamp(progress)
            bar.progress(this.progress)
        }

        override fun color(color: BossBarColor) {
            bar.color(colorOf(color))
        }

        override fun overlay(overlay: BossBarOverlay) {
            bar.overlay(overlayOf(overlay))
        }

        override fun properties(darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean) {
            val flags = mutableSetOf<BossBar.Flag>()
            if (darkenScreen) flags.add(BossBar.Flag.DARKEN_SCREEN)
            if (playBossMusic) flags.add(BossBar.Flag.PLAY_BOSS_MUSIC)
            if (createWorldFog) flags.add(BossBar.Flag.CREATE_WORLD_FOG)
            bar.flags(flags)
        }

        @Synchronized
        override fun animateProgress(target: Float, duration: Duration, easing: Easing) {
            cancel(animation)
            val start = progress
            val end = clamp(target)
            val steps = maxOf(1, (valid(duration).toMillis() / 50).toInt())
            val step = AtomicInteger()
            val curve = easing
            animation = scheduler.scheduleAtFixedRate({
                if (paused()) return@scheduleAtFixedRate
                val ratio = minOf(1f, step.incrementAndGet() / steps.toFloat())
                progress(start + (end - start) * curve.apply(ratio))
                if (ratio >= 1f) cancel(animation)
            }, 0, 50, TimeUnit.MILLISECONDS)
        }

        override fun pause() {
            if (!isCompletedActive || isCompletedPaused) return
            super.pause()
            subscribedPlayer?.hideBossBar(bar)
        }

        override fun resume() {
            if (!isCompletedActive || !isCompletedPaused) return
            super.resume()
            showNow()
        }

        @Synchronized
        private fun showNow() {
            val p = player()
            if (isCompletedActive && !isCompletedPaused && p != null) {
                if (subscribedPlayer != p) {
                    subscribedPlayer?.hideBossBar(bar)
                    subscribedPlayer = p
                }
                p.showBossBar(bar)
            }
        }

        @Synchronized
        override fun close() {
            if (!isCompletedActive) return
            isCompletedActive = false
            bosses.remove(key, this)
            cancel(animation)
            cancel(expiry)
            subscribedPlayer?.hideBossBar(bar)
            subscribedPlayer = null
        }
    }

    companion object {
        private fun valid(value: Duration?): Duration = if (value == null || value.isNegative) Duration.ZERO else value

        private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)

        private fun colorOf(color: BossBarColor?): BossBar.Color {
            return when (color ?: BossBarColor.PURPLE) {
                BossBarColor.PINK -> BossBar.Color.PINK
                BossBarColor.BLUE -> BossBar.Color.BLUE
                BossBarColor.RED -> BossBar.Color.RED
                BossBarColor.GREEN -> BossBar.Color.GREEN
                BossBarColor.YELLOW -> BossBar.Color.YELLOW
                BossBarColor.PURPLE -> BossBar.Color.PURPLE
                BossBarColor.WHITE -> BossBar.Color.WHITE
            }
        }

        private fun overlayOf(overlay: BossBarOverlay?): BossBar.Overlay {
            return when (overlay ?: BossBarOverlay.PROGRESS) {
                BossBarOverlay.PROGRESS -> BossBar.Overlay.PROGRESS
                BossBarOverlay.NOTCHED_6 -> BossBar.Overlay.NOTCHED_6
                BossBarOverlay.NOTCHED_10 -> BossBar.Overlay.NOTCHED_10
                BossBarOverlay.NOTCHED_12 -> BossBar.Overlay.NOTCHED_12
                BossBarOverlay.NOTCHED_20 -> BossBar.Overlay.NOTCHED_20
            }
        }
    }
}
