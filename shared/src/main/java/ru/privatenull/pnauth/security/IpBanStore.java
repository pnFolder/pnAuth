package ru.privatenull.pnauth.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IpBanStore {
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final Clock clock;

    public IpBanStore() {
        this(Clock.systemUTC());
    }

    public IpBanStore(Clock clock) {
        this.clock = clock;
    }

    public void ban(String ip, Duration duration) {
        if (ip != null && !ip.isBlank()) bans.put(ip, clock.millis() + duration.toMillis());
    }

    public boolean isBanned(String ip) {
        Long until = bans.get(ip);
        if (until == null) return false;
        if (until <= clock.millis()) {
            bans.remove(ip, until);
            return false;
        }
        return true;
    }

    public void clear() {
        bans.clear();
    }
}
