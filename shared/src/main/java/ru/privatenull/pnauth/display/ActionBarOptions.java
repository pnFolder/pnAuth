package ru.privatenull.pnauth.display;
import java.time.Duration;
public record ActionBarOptions(String text, Duration refreshInterval, Duration lifetime) {
    public ActionBarOptions { text = text == null ? "" : text; refreshInterval = duration(refreshInterval); lifetime = duration(lifetime); }
    public static ActionBarOptions once(String text) { return new ActionBarOptions(text, Duration.ZERO, Duration.ZERO); }
    private static Duration duration(Duration value) { return value == null || value.isNegative() ? Duration.ZERO : value; }
}
