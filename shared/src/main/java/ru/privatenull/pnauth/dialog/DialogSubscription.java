package ru.privatenull.pnauth.dialog;

/** Registration for a stream of dialog responses. */
@FunctionalInterface
public interface DialogSubscription extends AutoCloseable {
    @Override void close();
}
