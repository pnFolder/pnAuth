package ru.privatenull.pnauth.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.config.CaptchaSettings
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ClickCaptchaServiceTest {
    @Test
    fun acceptsOnlyThePlayerBoundOneTimeToken() {
        val captcha = ClickCaptchaService(
            CaptchaSettings(true, Duration.ofSeconds(30), 3)
        )
        val player = UUID.randomUUID()
        val challenge = captcha.issue(player)
        val correct = challenge.options.first { it.correct }

        assertFalse(captcha.verified(player))
        assertEquals(
            ClickCaptchaService.Result.EXPIRED,
            captcha.verify(UUID.randomUUID(), correct.token)
        )
        assertEquals(ClickCaptchaService.Result.SUCCESS, captcha.verify(player, correct.token))
        assertTrue(captcha.verified(player))
        assertEquals(ClickCaptchaService.Result.EXPIRED, captcha.verify(player, correct.token))
    }

    @Test
    fun locksChallengeAfterConfiguredWrongAttempts() {
        val captcha = ClickCaptchaService(
            CaptchaSettings(true, Duration.ofSeconds(30), 2)
        )
        val player = UUID.randomUUID()
        val challenge = captcha.issue(player)
        val wrong = challenge.options.first { !it.correct }

        assertEquals(ClickCaptchaService.Result.INVALID, captcha.verify(player, wrong.token))
        assertEquals(ClickCaptchaService.Result.LOCKED, captcha.verify(player, wrong.token))
        assertFalse(captcha.verified(player))
    }

    @Test
    fun acceptsConcurrentCorrectAnswerOnlyOnce() {
        val captcha = ClickCaptchaService(
            CaptchaSettings(true, Duration.ofSeconds(30), 3)
        )
        val player = UUID.randomUUID()
        val correct = captcha.issue(player).options.first { it.correct }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempt = Callable { captcha.verify(player, correct.token) }
            val first = executor.submit(attempt)
            val second = executor.submit(attempt)

            val successes = (if (first.get() == ClickCaptchaService.Result.SUCCESS) 1 else 0) +
                    (if (second.get() == ClickCaptchaService.Result.SUCCESS) 1 else 0)
            assertEquals(1, successes)
            assertTrue(captcha.verified(player))
        } finally {
            executor.shutdownNow()
        }
    }
}
