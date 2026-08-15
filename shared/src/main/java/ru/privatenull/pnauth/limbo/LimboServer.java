package ru.privatenull.pnauth.limbo;

import java.util.Optional;

public interface LimboServer extends AutoCloseable {
    String id();

    String host();

    int port();

    LimboServerState state();

    default Optional<LimboControl> control() {
        return Optional.empty();
    }

    void start() throws Exception;

    void stop();

    @Override
    default void close() {
        stop();
    }
}
