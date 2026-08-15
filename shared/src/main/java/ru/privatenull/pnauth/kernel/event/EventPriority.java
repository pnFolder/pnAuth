package ru.privatenull.pnauth.kernel.event;
public record EventPriority(String name, int value) implements Comparable<EventPriority> {
    public static final int MIN_CUSTOM = -5_000;
    public static final int MAX_CUSTOM = 5_000;
    public static final EventPriority LOWEST = new EventPriority("LOWEST", -1_000);
    public static final EventPriority LOW = new EventPriority("LOW", -500);
    public static final EventPriority NORMAL = new EventPriority("NORMAL", 0);
    public static final EventPriority HIGH = new EventPriority("HIGH", 500);
    public static final EventPriority HIGHEST = new EventPriority("HIGHEST", 1_000);
    /** Reserved for pnAuth invariants; third-party custom priorities cannot reach it. */
    public static final EventPriority SYSTEM = new EventPriority("SYSTEM", 9_000);
    /** Always last and read-only. */
    public static final EventPriority MONITOR = new EventPriority("MONITOR", 10_000);
    public EventPriority {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("priority name is required");
        if (value < -10_000 || value > 10_000) throw new IllegalArgumentException("priority must be between -10000 and 10000");
    }
    public static EventPriority custom(String name, int value) {
        if (value < MIN_CUSTOM || value > MAX_CUSTOM) throw new IllegalArgumentException(
                "custom priority must be between " + MIN_CUSTOM + " and " + MAX_CUSTOM);
        return new EventPriority(name, value);
    }
    @Override public int compareTo(EventPriority other) { return Integer.compare(value, other.value); }
}
