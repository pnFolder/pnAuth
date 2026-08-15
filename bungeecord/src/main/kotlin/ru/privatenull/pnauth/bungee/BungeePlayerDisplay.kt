package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.protocol.packet.BossBar
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
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class BungeePlayerDisplay(
    private val proxy: ProxyServer,
    private val format: MessageFormat
) : PlayerDisplay, AutoCloseable {

    private val actions: ConcurrentMap<PlayerResourceKey, Action> = ConcurrentHashMap()
    private val titles: ConcurrentMap<PlayerResourceKey, Titles> = ConcurrentHashMap()
    private val bosses: ConcurrentMap<PlayerResourceKey, Boss> = ConcurrentHashMap()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2, ThreadFactory { runnable ->
        val thread = Thread(runnable, "pnauth-bungee-display")
        thread.isDaemon = true
        thread
    })

    override fun actionBar(id: UUID, name: String, options: ActionBarOptions): ActionBarHandle {
        val k = key(id, name)
        return actions.compute(k) { _, v ->
            if (v == null || !v.active()) Action(k, options)
            else {
                v.applyOptions(options)
                v
            }
        }!!
    }

    override fun title(id: UUID, name: String, options: TitleOptions): TitleHandle {
        val k = key(id, name)
        return titles.compute(k) { _, v ->
            if (v == null || !v.active()) Titles(k, options)
            else {
                v.applyOptions(options)
                v
            }
        }!!
    }

    override fun bossBar(id: UUID, name: String, options: BossBarOptions): BossBarHandle {
        val k = key(id, name)
        return bosses.compute(k) { _, v ->
            if (v == null || !v.active()) Boss(k, options)
            else {
                v.applyOptions(options)
                v
            }
        }!!
    }

    override fun findActionBar(id: UUID, name: String): Optional<ActionBarHandle> =
        Optional.ofNullable(actions[key(id, name)])

    override fun findTitle(id: UUID, name: String): Optional<TitleHandle> =
        Optional.ofNullable(titles[key(id, name)])

    override fun findBossBar(id: UUID, name: String): Optional<BossBarHandle> =
        Optional.ofNullable(bosses[key(id, name)])

    override fun removeActionBar(id: UUID, name: String): Boolean {
        val h = actions.remove(key(id, name))
        h?.close()
        return h != null
    }

    override fun removeTitle(id: UUID, name: String): Boolean {
        val h = titles.remove(key(id, name))
        h?.close()
        return h != null
    }

    override fun removeBossBar(id: UUID, name: String): Boolean {
        val h = bosses.remove(key(id, name))
        h?.close()
        return h != null
    }

    override fun clear(id: UUID) {
        actions.entries.stream().filter { it.key.playerId == id }.map { it.value }.toList().forEach { it.close() }
        titles.entries.stream().filter { it.key.playerId == id }.map { it.value }.toList().forEach { it.close() }
        bosses.entries.stream().filter { it.key.playerId == id }.map { it.value }.toList().forEach { it.close() }
    }

    override fun close() {
        actions.values.forEach { it.close() }
        titles.values.forEach { it.close() }
        bosses.values.forEach { it.close() }
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
            expiry?.cancel(false)
            if (!lifetime.isZero && !lifetime.isNegative) {
                expiry = scheduler.schedule({ close() }, lifetime.toMillis(), TimeUnit.MILLISECONDS)
            }
        }

        fun player(): ProxiedPlayer? = proxy.getPlayer(playerId)
        fun cancel(task: ScheduledFuture<*>?) {
            task?.cancel(false)
        }
    }

    private inner class Action(
        key: PlayerResourceKey,
        options: ActionBarOptions
    ) : Base(key, options.lifetime()), ActionBarHandle {
        @Volatile private var actionText: String = ""
        @Volatile private var actionInterval: Duration = Duration.ZERO
        @Volatile private var refresh: ScheduledFuture<*>? = null

        init {
            actionText = options.text()
            actionInterval = options.refreshInterval()
            sendNow()
            reschedule()
        }

        fun applyOptions(o: ActionBarOptions) {
            text(o.text())
            refreshInterval(o.refreshInterval())
            lifetime(o.lifetime())
        }

        override fun text(text: String) {
            actionText = text
            sendNow()
        }

        @Synchronized
        override fun refreshInterval(interval: Duration) {
            actionInterval = valid(interval)
            reschedule()
        }

        @Synchronized
        private fun reschedule() {
            cancel(refresh)
            if (isCompletedActive && !actionInterval.isZero) {
                refresh = scheduler.scheduleAtFixedRate(
                    { sendNow() },
                    actionInterval.toMillis(),
                    actionInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            }
        }

        override fun sendNow() {
            val p = player()
            if (isCompletedActive && !isCompletedPaused && p != null) {
                p.sendMessage(ChatMessageType.ACTION_BAR, BungeeMessages.component(actionText, format))
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
            p?.sendMessage(ChatMessageType.ACTION_BAR, TextComponent(""))
        }
    }

    private inner class Titles(
        key: PlayerResourceKey,
        options: TitleOptions
    ) : Base(key, options.lifetime()), TitleHandle {
        @Volatile private var titleText: String = options.title()
        @Volatile private var subtitleText: String = options.subtitle()
        @Volatile private var fadeInDuration: Duration = options.fadeIn()
        @Volatile private var stayDuration: Duration = options.stay()
        @Volatile private var fadeOutDuration: Duration = options.fadeOut()
        @Volatile private var repeatDuration: Duration = options.repeatInterval()
        @Volatile private var refresh: ScheduledFuture<*>? = null

        init {
            showNow()
            reschedule()
        }

        fun applyOptions(o: TitleOptions) {
            titleText = o.title()
            subtitleText = o.subtitle()
            timings(o.fadeIn(), o.stay(), o.fadeOut())
            repeatInterval(o.repeatInterval())
            lifetime(o.lifetime())
            showNow()
        }

        override fun title(title: String) {
            titleText = title
            showNow()
        }

        override fun subtitle(subtitle: String) {
            subtitleText = subtitle
            showNow()
        }

        override fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration) {
            fadeInDuration = valid(fadeIn)
            stayDuration = valid(stay)
            fadeOutDuration = valid(fadeOut)
        }

        @Synchronized
        override fun repeatInterval(interval: Duration) {
            repeatDuration = valid(interval)
            reschedule()
        }

        @Synchronized
        private fun reschedule() {
            cancel(refresh)
            if (isCompletedActive && !repeatDuration.isZero) {
                refresh = scheduler.scheduleAtFixedRate(
                    { showNow() },
                    repeatDuration.toMillis(),
                    repeatDuration.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            }
        }

        override fun showNow() {
            val p = player()
            if (isCompletedActive && !isCompletedPaused && p != null) {
                p.sendTitle(
                    proxy.createTitle()
                        .title(BungeeMessages.component(titleText, format))
                        .subTitle(BungeeMessages.component(subtitleText, format))
                        .fadeIn(ticks(fadeInDuration))
                        .stay(ticks(stayDuration))
                        .fadeOut(ticks(fadeOutDuration))
                )
            }
        }

        override fun clear() {
            val p = player()
            p?.sendTitle(proxy.createTitle().clear())
        }

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
        options: BossBarOptions
    ) : Base(key, options.lifetime()), BossBarHandle {

        val barId: UUID = UUID.nameUUIDFromBytes("${key.playerId}:${key.name}".toByteArray(StandardCharsets.UTF_8))
        @Volatile private var bossText: String = options.text()
        @Volatile private var bossProgress: Float = options.progress()
        @Volatile private var bossColor: BossBarColor = options.color()
        @Volatile private var bossOverlay: BossBarOverlay = options.overlay()
        @Volatile private var flags: Byte = ((if (options.darkenScreen()) 1 else 0) or
                (if (options.playBossMusic()) 2 else 0) or
                (if (options.createWorldFog()) 4 else 0)).toByte()
        @Volatile private var animation: ScheduledFuture<*>? = null

        init {
            packet(0)
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
            bossText = text
            packet(3)
        }

        override fun progress(progress: Float) {
            bossProgress = clamp(progress)
            packet(2)
        }

        override fun color(color: BossBarColor) {
            bossColor = color
            packet(4)
        }

        override fun overlay(overlay: BossBarOverlay) {
            bossOverlay = overlay
            packet(4)
        }

        override fun properties(darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean) {
            flags = ((if (darkenScreen) 1 else 0) or (if (playBossMusic) 2 else 0) or (if (createWorldFog) 4 else 0)).toByte()
            packet(5)
        }

        @Synchronized
        override fun animateProgress(target: Float, duration: Duration, easing: Easing) {
            cancel(animation)
            val start = bossProgress
            val end = clamp(target)
            val steps = Math.max(1, (valid(duration).toMillis() / 50).toInt())
            val step = AtomicInteger()
            val curve = easing
            animation = scheduler.scheduleAtFixedRate({
                if (isCompletedPaused) return@scheduleAtFixedRate
                val ratio = Math.min(1f, step.incrementAndGet() / steps.toFloat())
                progress(start + (end - start) * curve.apply(ratio))
                if (ratio >= 1f) cancel(animation)
            }, 0, 50, TimeUnit.MILLISECONDS)
        }

        override fun pause() {
            if (isCompletedActive && !isCompletedPaused) packet(1)
            isCompletedPaused = true
        }

        override fun resume() {
            if (isCompletedActive && isCompletedPaused) {
                isCompletedPaused = false
                packet(0)
            }
        }

        private fun packet(action: Int) {
            val p = player()
            if (!isCompletedActive || isCompletedPaused || p == null) return
            val packet = BossBar(barId, action)
            packet.title = BungeeMessages.component(bossText, format)
            packet.health = bossProgress
            packet.color = bossColor.ordinal
            packet.division = bossOverlay.ordinal
            packet.flags = flags
            p.unsafe().sendPacket(packet)
        }

        @Synchronized
        override fun close() {
            if (!isCompletedActive) return
            packet(1)
            isCompletedActive = false
            bosses.remove(key, this)
            cancel(animation)
            cancel(expiry)
        }
    }

    companion object {
        private fun valid(value: Duration?): Duration =
            if (value == null || value.isNegative) Duration.ZERO else value

        private fun ticks(value: Duration?): Int =
            Math.max(0, (valid(value).toMillis() / 50).toInt())

        private fun clamp(value: Float): Float =
            Math.max(0f, Math.min(1f, value))

        private fun key(id: UUID, name: String): PlayerResourceKey = PlayerResourceKey(id, name)
    }
}
