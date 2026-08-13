package ru.privatenull.pnauth.limbo;

public interface LimboServerProvider {
    String id();

    LimboServer create(LimboServerContext context);
}
