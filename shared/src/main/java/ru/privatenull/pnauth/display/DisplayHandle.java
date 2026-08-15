package ru.privatenull.pnauth.display;
import java.time.Duration;
import java.util.UUID;
public interface DisplayHandle extends AutoCloseable {
    UUID playerId();
    String displayId();
    boolean active();
    boolean paused();
    void pause();
    void resume();
    void lifetime(Duration lifetime);
    @Override void close();
}
