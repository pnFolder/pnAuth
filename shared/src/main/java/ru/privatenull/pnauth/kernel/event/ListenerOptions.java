package ru.privatenull.pnauth.kernel.event;
public record ListenerOptions(String ownerId, EventPriority priority, boolean receiveCancelled, ListenerMode mode) {
    public ListenerOptions {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        priority = priority == null ? EventPriority.NORMAL : priority;
        mode = mode == null ? ListenerMode.MUTATING : mode;
        if (priority.equals(EventPriority.MONITOR)) mode = ListenerMode.MONITOR;
        if (mode == ListenerMode.MUTATING && priority.value() > EventPriority.MAX_CUSTOM
                && !ownerId.equals("pnauth:system")) throw new IllegalArgumentException("reserved priority");
    }
    public static ListenerOptions normal(String ownerId) {
        return new ListenerOptions(ownerId, EventPriority.NORMAL, false, ListenerMode.MUTATING);
    }
    public static ListenerOptions monitor(String ownerId) {
        return new ListenerOptions(ownerId, EventPriority.MONITOR, true, ListenerMode.MONITOR);
    }
}
