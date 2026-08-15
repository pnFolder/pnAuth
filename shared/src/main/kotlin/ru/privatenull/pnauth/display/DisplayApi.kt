package ru.privatenull.pnauth.display

import java.time.Duration
import java.util.Optional
import java.util.UUID

/** A mutable, server-owned UI element with a stable identity for one player. */
interface DisplayHandle : AutoCloseable {
    fun playerId(): UUID
    fun displayId(): String
    fun active(): Boolean
    fun paused(): Boolean
    fun pause()
    fun resume()
    fun lifetime(lifetime: Duration)
    override fun close()
}

/** Controls a persistent action bar. */
interface ActionBarHandle : DisplayHandle {
    fun text(text: String)
    fun refreshInterval(interval: Duration)
    fun sendNow()
}

/** Controls a title/subtitle presentation. */
interface TitleHandle : DisplayHandle {
    fun title(title: String)
    fun subtitle(subtitle: String)
    fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration)
    fun repeatInterval(interval: Duration)
    fun showNow()
    fun clear()
    /** Releases the server-side handle without sending a clear packet. */
    fun release()
}

/** Controls a persistent boss bar. */
interface BossBarHandle : DisplayHandle {
    fun text(text: String)
    fun progress(progress: Float)
    fun color(color: BossBarColor)
    fun overlay(overlay: BossBarOverlay)
    fun properties(darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean)
    fun animateProgress(target: Float, duration: Duration, easing: Easing)
}

/** Creates, updates and removes display elements by a caller-chosen stable identifier. */
interface PlayerDisplay {
    fun actionBar(playerId: UUID, displayId: String, options: ActionBarOptions): ActionBarHandle
    fun title(playerId: UUID, displayId: String, options: TitleOptions): TitleHandle
    fun bossBar(playerId: UUID, displayId: String, options: BossBarOptions): BossBarHandle
    fun findActionBar(playerId: UUID, displayId: String): Optional<ActionBarHandle>
    fun findTitle(playerId: UUID, displayId: String): Optional<TitleHandle>
    fun findBossBar(playerId: UUID, displayId: String): Optional<BossBarHandle>
    fun removeActionBar(playerId: UUID, displayId: String): Boolean
    fun removeTitle(playerId: UUID, displayId: String): Boolean
    fun removeBossBar(playerId: UUID, displayId: String): Boolean
    fun clear(playerId: UUID)

    fun showActionBar(playerId: UUID, options: ActionBarOptions): ActionBarHandle = actionBar(playerId, UUID.randomUUID().toString(), options)
    fun showTitle(playerId: UUID, options: TitleOptions): TitleHandle = title(playerId, UUID.randomUUID().toString(), options)
    fun showBossBar(playerId: UUID, options: BossBarOptions): BossBarHandle = bossBar(playerId, UUID.randomUUID().toString(), options)
}

class ActionBarOptions(text: String?, refreshInterval: Duration?, lifetime: Duration?) {
    private val valueText = text ?: ""
    private val valueRefreshInterval = valid(refreshInterval)
    private val valueLifetime = valid(lifetime)
    fun text(): String = valueText
    fun refreshInterval(): Duration = valueRefreshInterval
    fun lifetime(): Duration = valueLifetime
    companion object { @JvmStatic fun once(text: String?): ActionBarOptions = ActionBarOptions(text, Duration.ZERO, Duration.ZERO) }
}

class TitleOptions(title: String?, subtitle: String?, fadeIn: Duration?, stay: Duration?, fadeOut: Duration?, repeatInterval: Duration?, lifetime: Duration?) {
    private val valueTitle = title ?: ""
    private val valueSubtitle = subtitle ?: ""
    private val valueFadeIn = valid(fadeIn)
    private val valueStay = valid(stay)
    private val valueFadeOut = valid(fadeOut)
    private val valueRepeatInterval = valid(repeatInterval)
    private val valueLifetime = valid(lifetime)
    fun title(): String = valueTitle
    fun subtitle(): String = valueSubtitle
    fun fadeIn(): Duration = valueFadeIn
    fun stay(): Duration = valueStay
    fun fadeOut(): Duration = valueFadeOut
    fun repeatInterval(): Duration = valueRepeatInterval
    fun lifetime(): Duration = valueLifetime
}

class BossBarOptions(text: String?, progress: Float, color: BossBarColor?, overlay: BossBarOverlay?, darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean, lifetime: Duration?) {
    private val valueText = text ?: ""
    private val valueProgress = progress.coerceIn(0F, 1F)
    private val valueColor = color ?: BossBarColor.PURPLE
    private val valueOverlay = overlay ?: BossBarOverlay.PROGRESS
    private val valueDarkenScreen = darkenScreen
    private val valuePlayBossMusic = playBossMusic
    private val valueCreateWorldFog = createWorldFog
    private val valueLifetime = valid(lifetime)
    fun text(): String = valueText
    fun progress(): Float = valueProgress
    fun color(): BossBarColor = valueColor
    fun overlay(): BossBarOverlay = valueOverlay
    fun darkenScreen(): Boolean = valueDarkenScreen
    fun playBossMusic(): Boolean = valuePlayBossMusic
    fun createWorldFog(): Boolean = valueCreateWorldFog
    fun lifetime(): Duration = valueLifetime
}

enum class BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
enum class BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

enum class Easing {
    LINEAR { override fun apply(progress: Float) = progress },
    EASE_IN { override fun apply(progress: Float) = progress * progress },
    EASE_OUT { override fun apply(progress: Float) = 1F - (1F - progress) * (1F - progress) },
    EASE_IN_OUT { override fun apply(progress: Float) = if (progress < .5F) 2F * progress * progress else 1F - Math.pow((-2F * progress + 2F).toDouble(), 2.0).toFloat() / 2F };
    abstract fun apply(progress: Float): Float
}

/** A safe no-op implementation for platforms that cannot render player UI. */
class NoopPlayerDisplay : PlayerDisplay {
    override fun actionBar(playerId: UUID, displayId: String, options: ActionBarOptions): ActionBarHandle = Action(playerId, displayId)
    override fun title(playerId: UUID, displayId: String, options: TitleOptions): TitleHandle = Titles(playerId, displayId)
    override fun bossBar(playerId: UUID, displayId: String, options: BossBarOptions): BossBarHandle = Boss(playerId, displayId)
    override fun findActionBar(playerId: UUID, displayId: String): Optional<ActionBarHandle> = Optional.empty()
    override fun findTitle(playerId: UUID, displayId: String): Optional<TitleHandle> = Optional.empty()
    override fun findBossBar(playerId: UUID, displayId: String): Optional<BossBarHandle> = Optional.empty()
    override fun removeActionBar(playerId: UUID, displayId: String) = false
    override fun removeTitle(playerId: UUID, displayId: String) = false
    override fun removeBossBar(playerId: UUID, displayId: String) = false
    override fun clear(playerId: UUID) = Unit

    private abstract class Base(private val id: UUID, private val name: String) : DisplayHandle {
        private var isActive = true
        private var isPaused = false
        override fun playerId() = id
        override fun displayId() = name
        override fun active() = isActive
        override fun paused() = isPaused
        override fun pause() { isPaused = true }
        override fun resume() { isPaused = false }
        override fun lifetime(lifetime: Duration) = Unit
        override fun close() { isActive = false }
    }
    private class Action(id: UUID, name: String) : Base(id, name), ActionBarHandle {
        override fun text(text: String) = Unit; override fun refreshInterval(interval: Duration) = Unit; override fun sendNow() = Unit
    }
    private class Titles(id: UUID, name: String) : Base(id, name), TitleHandle {
        override fun title(title: String) = Unit; override fun subtitle(subtitle: String) = Unit
        override fun timings(fadeIn: Duration, stay: Duration, fadeOut: Duration) = Unit; override fun repeatInterval(interval: Duration) = Unit
        override fun showNow() = Unit; override fun clear() = Unit; override fun release() = Unit
    }
    private class Boss(id: UUID, name: String) : Base(id, name), BossBarHandle {
        override fun text(text: String) = Unit; override fun progress(progress: Float) = Unit; override fun color(color: BossBarColor) = Unit
        override fun overlay(overlay: BossBarOverlay) = Unit; override fun properties(darkenScreen: Boolean, playBossMusic: Boolean, createWorldFog: Boolean) = Unit
        override fun animateProgress(target: Float, duration: Duration, easing: Easing) = Unit
    }
}

private fun valid(value: Duration?): Duration = if (value == null || value.isNegative) Duration.ZERO else value
