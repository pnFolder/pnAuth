package ru.privatenull.pnauth.kernel.event;
final class EventDispatchScope {
    private static final ThreadLocal<ListenerOptions> CURRENT = new ThreadLocal<>();
    static ListenerOptions current() { return CURRENT.get(); }
    static void run(ListenerOptions options, Runnable action) {
        ListenerOptions previous = CURRENT.get(); CURRENT.set(options);
        try { action.run(); } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
