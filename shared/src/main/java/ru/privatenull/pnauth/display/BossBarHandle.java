package ru.privatenull.pnauth.display;
import java.time.Duration;
public interface BossBarHandle extends DisplayHandle {
    void text(String text);
    void progress(float progress);
    void color(BossBarColor color);
    void overlay(BossBarOverlay overlay);
    void properties(boolean darkenScreen, boolean playBossMusic, boolean createWorldFog);
    void animateProgress(float target, Duration duration, Easing easing);
}
