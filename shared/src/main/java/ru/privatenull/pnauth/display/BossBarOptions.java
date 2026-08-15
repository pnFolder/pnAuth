package ru.privatenull.pnauth.display;
import java.time.Duration;
public record BossBarOptions(String text, float progress, BossBarColor color, BossBarOverlay overlay,
                             boolean darkenScreen, boolean playBossMusic, boolean createWorldFog, Duration lifetime) {
    public BossBarOptions { text = text == null ? "" : text; progress = Math.max(0F, Math.min(1F, progress));
        color = color == null ? BossBarColor.PURPLE : color; overlay = overlay == null ? BossBarOverlay.PROGRESS : overlay;
        lifetime = lifetime == null || lifetime.isNegative() ? Duration.ZERO : lifetime; }
}
