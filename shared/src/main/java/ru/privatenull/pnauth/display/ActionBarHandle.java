package ru.privatenull.pnauth.display;
import java.time.Duration;
public interface ActionBarHandle extends DisplayHandle {
    void text(String text);
    void refreshInterval(Duration interval);
    void sendNow();
}
