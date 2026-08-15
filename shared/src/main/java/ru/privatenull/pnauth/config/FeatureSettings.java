package ru.privatenull.pnauth.config;

import java.time.Duration;
import java.util.Set;

public record FeatureSettings(
        boolean premiumEnabled,
        boolean restoreSessionOnSameIp,
        Duration sessionLifetime,
        Duration authTimeout,
        Duration reminderInterval,
        boolean banOnFailedLogin,
        Duration banDuration,
        int maxOnlineAccountsPerIp,
        int maxRegisteredAccountsPerIp,
        Set<String> excludedIps,
        boolean totpEnabled,
        int totpMaxAttempts,
        Duration totpLockoutDuration,
        Duration totpSetupLifetime,
        String totpIssuer,
        int recoveryCodesAmount,
        boolean repeatPasswordWhenRegister,
        DialogSettings dialogs,
        CaptchaSettings captcha,
        boolean titleEnabled,
        boolean actionBarEnabled
) {
    public FeatureSettings {
        excludedIps = excludedIps == null ? Set.of() : Set.copyOf(excludedIps);
        if (sessionLifetime.isNegative() || authTimeout.isNegative() || reminderInterval.isNegative()
                || banDuration.isNegative()
                || totpLockoutDuration.isNegative()
                || totpSetupLifetime == null || totpSetupLifetime.isZero() || totpSetupLifetime.isNegative()
                || maxOnlineAccountsPerIp < 1 || maxRegisteredAccountsPerIp < 1
                || totpMaxAttempts < 1 || recoveryCodesAmount < 1
                || totpIssuer == null || totpIssuer.isBlank() || dialogs == null || captcha == null) {
            throw new IllegalArgumentException("Invalid feature settings");
        }
    }

    public static FeatureSettings defaults() {
        return new FeatureSettings(
                true, false, Duration.ofMinutes(60), Duration.ofSeconds(60), Duration.ofSeconds(10), true, Duration.ofSeconds(60),
                10, 10, Set.of("127.0.0.1"),
                true, 3, Duration.ofSeconds(60), Duration.ofMinutes(5), "Minecraft Server", 16,
                true, DialogSettings.defaults(), CaptchaSettings.defaults(), false, false
        );
    }
}
