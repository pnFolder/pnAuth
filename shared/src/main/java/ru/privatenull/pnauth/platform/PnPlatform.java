package ru.privatenull.pnauth.platform;

import ru.privatenull.pnauth.dialog.PlayerDialogs;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Entry point for player and platform services exposed to extensions. */
public interface PnPlatform {
    PlatformType type();
    Optional<PnPlayer> player(UUID uniqueId);
    Optional<PnPlayer> player(String username);
    Collection<PnPlayer> players();
    PlatformScheduler scheduler();
    TaskRegistry tasks();
    PlayerDialogs dialogs();
}
