package ru.privatenull.pnauth.command;

import java.util.UUID;

public interface AuthPlatformBridge {
    AuthPlatformBridge NONE = new AuthPlatformBridge() {
        @Override
        public void authenticated(UUID uniqueId) {
        }

        @Override
        public void loggedOut(UUID uniqueId) {
        }

        @Override
        public void accountDeleted(UUID uniqueId) {
        }
    };

    void authenticated(UUID uniqueId);

    void loggedOut(UUID uniqueId);

    void accountDeleted(UUID uniqueId);

    default void authenticated(String username) {
    }

    default void accountDeleted(String username) {
    }

    /** Sends a pre-rendered announcement to every player connected to the proxy. */
    default void broadcast(String message) {
    }

    default void apply(CommandEffect effect, UUID uniqueId) {
        switch (effect) {
            case AUTHENTICATED -> authenticated(uniqueId);
            case LOGGED_OUT -> loggedOut(uniqueId);
            case ACCOUNT_DELETED -> accountDeleted(uniqueId);
            case NONE -> {
            }
        }
    }
}
