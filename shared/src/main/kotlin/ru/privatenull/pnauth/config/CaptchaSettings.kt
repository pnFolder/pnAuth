package ru.privatenull.pnauth.config

import java.time.Duration

@JvmRecord
data class CaptchaSettings(val enabled: Boolean, val lifetime: Duration, val maxAttempts: Int) {
    init {
        if (lifetime.isZero || lifetime.isNegative || maxAttempts < 1) {
            throw IllegalArgumentException("Invalid captcha settings")
        }
    }

    companion object {
        @JvmStatic
        fun defaults(): CaptchaSettings {
            return CaptchaSettings(false, Duration.ofSeconds(30), 3)
        }
    }
}
