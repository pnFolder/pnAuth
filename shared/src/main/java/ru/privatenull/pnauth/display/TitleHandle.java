package ru.privatenull.pnauth.display;
import java.time.Duration;
public interface TitleHandle extends DisplayHandle {
    void title(String title);
    void subtitle(String subtitle);
    void timings(Duration fadeIn, Duration stay, Duration fadeOut);
    void repeatInterval(Duration interval);
    void showNow();
    void clear();
    /** Releases the server-side handle without sending a clear packet to the client. */
    void release();
}
