package ru.privatenull.pnauth.limbo;

public interface LimboServer extends AutoCloseable {
    String id();

    String host();

    int port();

    LimboServerState state();

    void start() throws Exception;

    void stop();

    @Override
    default void close() {
        stop();
    }
}
