package ru.privatenull.pnauth.dialog;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** A visible dialog which can be replaced or closed by its owner. */
public interface DialogHandle extends AutoCloseable {
    UUID playerId();
    String dialogId();
    boolean active();
    CompletionStage<DialogResponse> response();
    DialogSubscription onResponse(Consumer<DialogResponse> listener);
    void replace(PlayerDialog dialog);
    @Override void close();
}
