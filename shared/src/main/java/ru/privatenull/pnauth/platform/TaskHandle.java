package ru.privatenull.pnauth.platform;

/** A task scheduled through the platform adapter. */
public interface TaskHandle extends AutoCloseable {
    boolean cancelled();
    void cancel();
    @Override default void close() { cancel(); }
}
