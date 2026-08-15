package ru.privatenull.pnauth.kernel.event;
/** Internal dispatch bridge used by event-bus implementations. */
public final class EventDispatchRunner {
    private EventDispatchRunner() { }
    public static void run(ListenerOptions options, Runnable action) { EventDispatchScope.run(options, action); }
}
