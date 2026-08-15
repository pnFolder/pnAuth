package ru.privatenull.pnauth.config

import java.time.Duration

@JvmRecord
data class FeatureSettings(
    val premiumEnabled: Boolean,
    val restoreSessionOnSameIp: Boolean,
    val sessionLifetime: Duration,
    val authTimeout: Duration,
    val reminderInterval: Duration,
    val banOnFailedLogin: Boolean,
    val banDuration: Duration,
    val maxOnlineAccountsPerIp: Int,
    val maxRegisteredAccountsPerIp: Int,
    val excludedIps: Set<String> = emptySet(),
    val totpEnabled: Boolean,
    val totpMaxAttempts: Int,
    val totpLockoutDuration: Duration,
    val totpSetupLifetime: Duration,
    val totpIssuer: String,
    val recoveryCodesAmount: Int,
    val repeatPasswordWhenRegister: Boolean,
    val dialogs: DialogSettings,
    val captcha: CaptchaSettings,
    val titleEnabled: Boolean,
    val actionBarEnabled: Boolean
) {
    init {
        if (sessionLifetime.isNegative || authTimeout.isNegative || reminderInterval.isNegative
            || banDuration.isNegative
            || totpLockoutDuration.isNegative
            || totpSetupLifetime.isZero || totpSetupLifetime.isNegative
            || maxOnlineAccountsPerIp < 1 || maxRegisteredAccountsPerIp < 1
            || totpMaxAttempts < 1 || recoveryCodesAmount < 1
            || totpIssuer.isBlank()
        ) {
            throw IllegalArgumentException("Invalid feature settings")
        }
    }

    companion object {
        @JvmStatic
        fun defaults(): FeatureSettings {
            return FeatureSettings(
                true, false, Duration.ofMinutes(60), Duration.ofSeconds(60), Duration.ofSeconds(10), true, Duration.ofSeconds(60),
                10, 10, setOf("127.0.0.1"),
                true, 3, Duration.ofSeconds(60), Duration.ofMinutes(5), "Minecraft Server", 16,
                true, DialogSettings.defaults(), CaptchaSettings.defaults(), false, false
            )
        }
    }
}
