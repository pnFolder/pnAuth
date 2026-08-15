package ru.privatenull.pnauth.config;

import java.time.Duration;

public record CaptchaSettings(boolean enabled, Duration lifetime, int maxAttempts) {
    public CaptchaSettings {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid captcha settings");
        }
    }

    public static CaptchaSettings defaults() {
        return new CaptchaSettings(false, Duration.ofSeconds(30), 3);
    }
}
