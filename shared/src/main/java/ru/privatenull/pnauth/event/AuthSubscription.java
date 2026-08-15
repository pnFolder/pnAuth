package ru.privatenull.pnauth.event;
@FunctionalInterface public interface AuthSubscription extends AutoCloseable { @Override void close(); }
