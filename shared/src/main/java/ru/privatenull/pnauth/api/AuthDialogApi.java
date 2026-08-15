package ru.privatenull.pnauth.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Client UI preference and capability negotiation. */
public interface AuthDialogApi {
    DialogPreference dialogPreference(UUID uniqueId);
    CompletableFuture<AuthResult> setDialogPreference(UUID uniqueId, DialogPreference preference);
    boolean shouldUseDialog(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs);

    default boolean shouldUseCommandFallback(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs) {
        return !shouldUseDialog(uniqueId, clientProtocol, platformSupportsDialogs);
    }
}
