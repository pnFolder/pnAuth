package ru.privatenull.pnauth.velocity.dialog;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/** @deprecated Legacy factory retained for rollback reference. */
@Deprecated(forRemoval = false)
final class VelocityDialogServiceFactory {
    private VelocityDialogServiceFactory() {
    }

    static VelocityDialogService create(ProxyServer proxy, Logger logger,
                                        VelocityDialogService.SubmissionHandler submissions) {
        try {
            Class<?> type = Class.forName(
                    "ru.privatenull.pnauth.velocity.dialog.PacketEventsVelocityDialogService",
                    true, VelocityDialogServiceFactory.class.getClassLoader());
            return (VelocityDialogService) type
                    .getConstructor(VelocityDialogService.SubmissionHandler.class)
                    .newInstance(submissions);
        } catch (ReflectiveOperationException | LinkageError exception) {
            logger.warn("Bundled PacketEvents dialog integration failed; using commands.", exception);
            return UnavailableDialogService.INSTANCE;
        }
    }

    private enum UnavailableDialogService implements VelocityDialogService {
        INSTANCE;
        public boolean available() { return false; }
        public void show(Player player, DialogForm form) { }
        public void clear(Player player) { }
    }
}
