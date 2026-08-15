package ru.privatenull.pnauth.security;

import ru.privatenull.pnauth.config.CaptchaSettings;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-time, player-bound click captcha. Command arguments contain random tokens, never the answer. */
public final class ClickCaptchaService {
    private final CaptchaSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public ClickCaptchaService(CaptchaSettings settings) {
        this(settings, Clock.systemUTC());
    }

    ClickCaptchaService(CaptchaSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean verified(UUID playerId) {
        if (!settings.enabled()) return true;
        State state = states.get(playerId);
        return state != null && state.verified();
    }

    public Challenge issue(UUID playerId) {
        int answer = 100 + random.nextInt(900);
        List<Option> options = new ArrayList<>();
        options.add(option(answer, true));
        while (options.size() < 3) {
            int value = 100 + random.nextInt(900);
            if (options.stream().noneMatch(option -> option.label().equals(String.valueOf(value)))) {
                options.add(option(value, false));
            }
        }
        Collections.shuffle(options, random);
        State state = new State(answer, options, clock.millis() + settings.lifetime().toMillis(), 0, false);
        states.put(playerId, state);
        return new Challenge(String.valueOf(answer), List.copyOf(options));
    }

    public Result verify(UUID playerId, String token) {
        while (true) {
            State state = states.get(playerId);
            if (state == null || state.verified()) return Result.EXPIRED;
            if (clock.millis() > state.expiresAt()) {
                if (states.remove(playerId, state)) return Result.EXPIRED;
                continue;
            }
            Option selected = state.options().stream()
                    .filter(option -> option.token().equals(token)).findFirst().orElse(null);
            if (selected != null && selected.correct()) {
                if (states.replace(playerId, state, state.asVerified())) return Result.SUCCESS;
                continue;
            }
            int attempts = state.attempts() + 1;
            if (attempts >= settings.maxAttempts()) {
                if (states.remove(playerId, state)) return Result.LOCKED;
                continue;
            }
            State next = new State(state.answer(), state.options(), state.expiresAt(), attempts, false);
            if (states.replace(playerId, state, next)) return Result.INVALID;
        }
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
    }

    public void clearAll() {
        states.clear();
    }

    private Option option(int label, boolean correct) {
        return new Option(String.valueOf(label), UUID.randomUUID().toString().replace("-", ""), correct);
    }

    public record Challenge(String answer, List<Option> options) {}
    public record Option(String label, String token, boolean correct) {}
    public enum Result { SUCCESS, INVALID, EXPIRED, LOCKED }
    private record State(int answer, List<Option> options, long expiresAt, int attempts, boolean verified) {
        private State asVerified() {
            return new State(answer, List.of(), Long.MAX_VALUE, attempts, true);
        }
    }
}
