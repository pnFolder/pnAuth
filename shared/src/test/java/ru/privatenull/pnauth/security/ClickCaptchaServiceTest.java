package ru.privatenull.pnauth.security;

import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.config.CaptchaSettings;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickCaptchaServiceTest {
    @Test
    void acceptsOnlyThePlayerBoundOneTimeToken() {
        ClickCaptchaService captcha = new ClickCaptchaService(
                new CaptchaSettings(true, Duration.ofSeconds(30), 3));
        UUID player = UUID.randomUUID();
        ClickCaptchaService.Challenge challenge = captcha.issue(player);
        ClickCaptchaService.Option correct = challenge.options().stream()
                .filter(ClickCaptchaService.Option::correct).findFirst().orElseThrow();

        assertFalse(captcha.verified(player));
        assertEquals(ClickCaptchaService.Result.EXPIRED,
                captcha.verify(UUID.randomUUID(), correct.token()));
        assertEquals(ClickCaptchaService.Result.SUCCESS, captcha.verify(player, correct.token()));
        assertTrue(captcha.verified(player));
        assertEquals(ClickCaptchaService.Result.EXPIRED, captcha.verify(player, correct.token()));
    }

    @Test
    void locksChallengeAfterConfiguredWrongAttempts() {
        ClickCaptchaService captcha = new ClickCaptchaService(
                new CaptchaSettings(true, Duration.ofSeconds(30), 2));
        UUID player = UUID.randomUUID();
        ClickCaptchaService.Challenge challenge = captcha.issue(player);
        ClickCaptchaService.Option wrong = challenge.options().stream()
                .filter(option -> !option.correct()).findFirst().orElseThrow();

        assertEquals(ClickCaptchaService.Result.INVALID, captcha.verify(player, wrong.token()));
        assertEquals(ClickCaptchaService.Result.LOCKED, captcha.verify(player, wrong.token()));
        assertFalse(captcha.verified(player));
    }

    @Test
    void acceptsConcurrentCorrectAnswerOnlyOnce() throws Exception {
        ClickCaptchaService captcha = new ClickCaptchaService(
                new CaptchaSettings(true, Duration.ofSeconds(30), 3));
        UUID player = UUID.randomUUID();
        ClickCaptchaService.Option correct = captcha.issue(player).options().stream()
                .filter(ClickCaptchaService.Option::correct).findFirst().orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ClickCaptchaService.Result> attempt = () -> captcha.verify(player, correct.token());
            Future<ClickCaptchaService.Result> first = executor.submit(attempt);
            Future<ClickCaptchaService.Result> second = executor.submit(attempt);

            int successes = (first.get() == ClickCaptchaService.Result.SUCCESS ? 1 : 0)
                    + (second.get() == ClickCaptchaService.Result.SUCCESS ? 1 : 0);
            assertEquals(1, successes);
            assertTrue(captcha.verified(player));
        } finally {
            executor.shutdownNow();
        }
    }
}
