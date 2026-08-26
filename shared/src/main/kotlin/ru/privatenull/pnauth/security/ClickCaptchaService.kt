package ru.privatenull.pnauth.security

import ru.privatenull.pnauth.config.CaptchaSettings
import java.security.SecureRandom
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One-time, player-bound click captcha. Command arguments contain random tokens, never the answer. */
class ClickCaptchaService @JvmOverloads constructor(
    private val settings: CaptchaSettings,
    private val clock: Clock = Clock.systemUTC()
) {
    private val random = SecureRandom()
    private val states: MutableMap<UUID, State> = ConcurrentHashMap()

    fun enabled(): Boolean = settings.enabled

    fun verified(playerId: UUID): Boolean {
        if (!settings.enabled) return true
        val state = states[playerId]
        return state != null && state.verified
    }

    fun issue(playerId: UUID): Challenge {
        val answer = 100 + random.nextInt(900)
        val options = mutableListOf<Option>()
        options.add(option(answer, true))
        while (options.size < 3) {
            val value = 100 + random.nextInt(900)
            if (options.none { it.label == value.toString() }) {
                options.add(option(value, false))
            }
        }
        Collections.shuffle(options, random)
        val state = State(answer, options, clock.millis() + settings.lifetime.toMillis(), 0, false)
        states[playerId] = state
        return Challenge(answer.toString(), java.util.List.copyOf(options))
    }

    fun verify(playerId: UUID, token: String?): Result {
        while (true) {
            val state = states[playerId] ?: return Result.EXPIRED
            if (state.verified) return Result.EXPIRED
            if (clock.millis() > state.expiresAt) {
                if (states.remove(playerId, state)) return Result.EXPIRED
                continue
            }
            val selected = state.options.firstOrNull { it.token == token }
            if (selected != null && selected.correct) {
                if (states.replace(playerId, state, state.asVerified())) return Result.SUCCESS
                continue
            }
            val attempts = state.attempts + 1
            if (attempts >= settings.maxAttempts) {
                if (states.remove(playerId, state)) return Result.LOCKED
                continue
            }
            val next = State(state.answer, state.options, state.expiresAt, attempts, false)
            if (states.replace(playerId, state, next)) return Result.INVALID
        }
    }

    fun clear(playerId: UUID) {
        states.remove(playerId)
    }

    fun clearAll() {
        states.clear()
    }

    private fun option(label: Int, correct: Boolean): Option {
        return Option(label.toString(), UUID.randomUUID().toString().replace("-", ""), correct)
    }

    @JvmRecord
    data class Challenge(val answer: String, val options: List<Option>)

    @JvmRecord
    data class Option(val label: String, val token: String, val correct: Boolean)

    enum class Result { SUCCESS, INVALID, EXPIRED, LOCKED }

    private data class State(
        val answer: Int,
        val options: List<Option>,
        val expiresAt: Long,
        val attempts: Int,
        val verified: Boolean
    ) {
        fun asVerified(): State = State(answer, emptyList(), Long.MAX_VALUE, attempts, true)
    }
}
