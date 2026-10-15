package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.exception.LoginRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, per-instance fixed-window limiter. Reservations count before password verification. */
public class LoginRateLimiter {
    private final Map<String, Window> windows = new HashMap<>();
    private final int ipLimit;
    private final int accountLimit;
    private final int maxEntries;
    private final Duration duration;
    private final Clock clock;

    public LoginRateLimiter(int ipLimit, int accountLimit, int maxEntries, Duration duration, Clock clock) {
        if (ipLimit < 1 || accountLimit < 1 || maxEntries < 2 || duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Login limits must be positive, capacity >= 2 and window >= 1 second");
        }
        this.ipLimit = ipLimit;
        this.accountLimit = accountLimit;
        this.maxEntries = maxEntries;
        this.duration = duration;
        this.clock = clock;
    }

    public void checkIp(String ip) { reserve("ip:" + ip, ipLimit); }

    public void checkAccount(String email) {
        // Avoid retaining raw email addresses in the limiter's keys.
        reserve("account:" + AuthSessionService.hash(email.trim().toLowerCase(Locale.ROOT)), accountLimit);
    }

    private synchronized void reserve(String key, int limit) {
        Instant now = clock.instant();
        Window window = windows.get(key);
        if (window == null || !window.endsAt.isAfter(now)) {
            if (window == null && windows.size() >= maxEntries) {
                windows.values().removeIf(value -> !value.endsAt.isAfter(now));
                if (windows.size() >= maxEntries) {
                    // Do not evict active counters: rotating identifiers must not bypass the limits.
                    Instant earliest = windows.values().stream().map(value -> value.endsAt)
                            .min(Instant::compareTo).orElseThrow();
                    throw new LoginRateLimitException(retryAfter(now, earliest));
                }
            }
            window = new Window(now.plus(duration));
            windows.put(key, window);
        }
        if (window.attempts >= limit) {
            throw new LoginRateLimitException(retryAfter(now, window.endsAt));
        }
        window.attempts++;
    }

    private long retryAfter(Instant now, Instant end) {
        Duration remaining = Duration.between(now, end);
        return Math.max(1, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
    }

    private static class Window {
        private final Instant endsAt;
        private int attempts;
        private Window(Instant endsAt) { this.endsAt = endsAt; }
    }
}
