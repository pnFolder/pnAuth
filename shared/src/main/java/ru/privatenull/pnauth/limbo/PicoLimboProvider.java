package ru.privatenull.pnauth.limbo;

public final class PicoLimboProvider implements LimboServerProvider {
    @Override
    public String id() {
        return "pico";
    }

    @Override
    public LimboServer create(LimboServerContext context) {
        return new PicoLimboServer(context.dataDirectory(), context.settings());
    }
}
