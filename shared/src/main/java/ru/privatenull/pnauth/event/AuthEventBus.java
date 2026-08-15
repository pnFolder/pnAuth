package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.kernel.event.ExtensionEvent;
import ru.privatenull.pnauth.kernel.event.EventListener;
import ru.privatenull.pnauth.kernel.event.ListenerOptions;
/** Shared event API. Dispatch is synchronous on the thread completing the authentication operation. */
public interface AuthEventBus {
    <E extends ExtensionEvent> AuthSubscription subscribe(Class<E> type, EventListener<? super E> listener);
    <E extends ExtensionEvent> AuthSubscription subscribe(
            Class<E> type, ListenerOptions options, EventListener<? super E> listener);
    void publish(ExtensionEvent event);
}
