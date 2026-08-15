package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.kernel.event.EventListener;
@FunctionalInterface public interface AuthEventListener<E extends AuthEvent> extends EventListener<E> { }
