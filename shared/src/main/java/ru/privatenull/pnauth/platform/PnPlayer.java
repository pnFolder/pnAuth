package ru.privatenull.pnauth.platform;

import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.display.PlayerDisplay;
import net.kyori.adventure.text.Component;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

/** Stable player facade which never exposes a Bungee, Velocity, or Bukkit type. */
public interface PnPlayer {
    UUID uniqueId();
    String username();
    InetSocketAddress remoteAddress();
    Optional<String> currentServer();
    boolean connected();
    boolean hasPermission(String permission);
    void sendMessage(String message);
    void sendMessage(Component message);
    void sendMessages(Iterable<? extends Component> messages);
    void disconnect(String reason);
    PlayerDisplay display();
    PlayerDialogs dialogs();
    PlatformScheduler scheduler();

    default String ipAddress() {
        return remoteAddress().getAddress() == null
                ? remoteAddress().getHostString()
                : remoteAddress().getAddress().getHostAddress();
    }
}
