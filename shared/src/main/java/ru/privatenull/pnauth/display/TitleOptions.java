package ru.privatenull.pnauth.display;
import java.time.Duration;
public record TitleOptions(String title, String subtitle, Duration fadeIn, Duration stay, Duration fadeOut,
                           Duration repeatInterval, Duration lifetime) {
    public TitleOptions { title = text(title); subtitle = text(subtitle); fadeIn = duration(fadeIn); stay = duration(stay);
        fadeOut = duration(fadeOut); repeatInterval = duration(repeatInterval); lifetime = duration(lifetime); }
    private static String text(String value) { return value == null ? "" : value; }
    private static Duration duration(Duration value) { return value == null || value.isNegative() ? Duration.ZERO : value; }
}
