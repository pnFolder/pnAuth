package ru.privatenull.pnauth.event;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import ru.privatenull.pnauth.kernel.event.ExtensionEvent;
import ru.privatenull.pnauth.kernel.event.EventListener;
import ru.privatenull.pnauth.kernel.event.ListenerOptions;
import ru.privatenull.pnauth.kernel.event.DecisionEvent;
import ru.privatenull.pnauth.kernel.event.EventDispatchRunner;
public final class SimpleAuthEventBus implements AuthEventBus {
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Registration>> listeners = new ConcurrentHashMap<>();
    private final BiConsumer<ExtensionEvent, Throwable> errorHandler;
    public SimpleAuthEventBus() { this((event, error) -> { }); }
    public SimpleAuthEventBus(BiConsumer<ExtensionEvent, Throwable> errorHandler) { this.errorHandler = Objects.requireNonNull(errorHandler); }
    @Override public <E extends ExtensionEvent> AuthSubscription subscribe(Class<E> type, EventListener<? super E> listener) {
        return subscribe(type, ListenerOptions.normal("anonymous"), listener);
    }
    @Override public <E extends ExtensionEvent> AuthSubscription subscribe(
            Class<E> type, ListenerOptions options, EventListener<? super E> listener) {
        Objects.requireNonNull(type); Objects.requireNonNull(listener);
        var registrations = listeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
        Registration registration = new Registration(options, listener);
        registrations.add(registration);
        registrations.sort(java.util.Comparator.comparingInt(value -> value.options.priority().value()));
        return () -> { registrations.remove(registration); if (registrations.isEmpty()) listeners.remove(type, registrations); };
    }
    @Override public void publish(ExtensionEvent event) {
        Objects.requireNonNull(event);
        java.util.ArrayList<Registration> dispatch = new java.util.ArrayList<>();
        listeners.forEach((type, registrations) -> { if (type.isInstance(event)) dispatch.addAll(registrations); });
        dispatch.sort(java.util.Comparator.comparingInt(value -> value.options.priority().value()));
        for (var registration : dispatch) {
            if (event instanceof DecisionEvent decision && decision.cancelled()
                    && !registration.options.receiveCancelled()) continue;
            dispatch(registration, event);
        }
    }
    @SuppressWarnings("unchecked") private void dispatch(Registration registration, ExtensionEvent event) {
        try { EventDispatchRunner.run(registration.options,
                () -> ((EventListener<ExtensionEvent>) registration.listener).onEvent(event)); }
        catch (Throwable error) { errorHandler.accept(event, error); }
    }
    private record Registration(ListenerOptions options, EventListener<?> listener) { }
}
