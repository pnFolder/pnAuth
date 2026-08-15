package ru.privatenull.pnauth.kernel.event;
@FunctionalInterface public interface EventListener<E extends ExtensionEvent> { void onEvent(E event); }
