package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.exception.LoginRateLimitException;
import java.time.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LoginRateLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, 2, 10, Duration.ofMinutes(1), clock);

    @Test
    void accountIsNormalizedAndIpHasIndependentLimit() {
        limiter.checkAccount(" User@example.com ");
        limiter.checkAccount("USER@example.com");
        assertThatThrownBy(() -> limiter.checkAccount("user@example.com")).isInstanceOf(LoginRateLimitException.class);
        limiter.checkAccount("another@example.com");
        for (int i = 0; i < 3; i++) limiter.checkIp("127.0.0.1");
        assertThatThrownBy(() -> limiter.checkIp("127.0.0.1")).isInstanceOf(LoginRateLimitException.class);
        limiter.checkIp("127.0.0.2");
    }

    @Test
    void blockedAttemptsDoNotExtendWindowAndRetryAfterRoundsUp() {
        limiter.checkAccount("user@example.com");
        limiter.checkAccount("user@example.com");
        clock.now = clock.now.plusMillis(1500);
        assertThatThrownBy(() -> limiter.checkAccount("user@example.com"))
                .isInstanceOfSatisfying(LoginRateLimitException.class, error ->
                        assertThat(error.getRetryAfterSeconds()).isEqualTo(59));
        clock.now = clock.now.plusMillis(58500);
        assertThatCode(() -> limiter.checkAccount("user@example.com")).doesNotThrowAnyException();
    }

    @Test
    void capacityDoesNotEvictActiveLimitsAndExpiredEntriesAreReclaimed() {
        var bounded = new LoginRateLimiter(1, 1, 2, Duration.ofMinutes(1), clock);
        bounded.checkIp("a");
        bounded.checkIp("b");
        assertThatThrownBy(() -> bounded.checkIp("c")).isInstanceOf(LoginRateLimitException.class);
        assertThatThrownBy(() -> bounded.checkIp("a")).isInstanceOf(LoginRateLimitException.class);
        clock.now = clock.now.plusSeconds(60);
        assertThatCode(() -> bounded.checkIp("c")).doesNotThrowAnyException();
    }

    @Test
    void simultaneousAttemptsCannotExceedLimit() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                start.await();
                try {
                    limiter.checkAccount("user@example.com");
                    return true;
                } catch (LoginRateLimitException exception) {
                    return false;
                }
            })).toList();
            start.countDown();
            int allowed = 0;
            for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(2);
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoginRateLimiter(0, 1, 2, Duration.ofMinutes(1), clock));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoginRateLimiter(1, 0, 2, Duration.ofMinutes(1), clock));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoginRateLimiter(1, 1, 1, Duration.ofMinutes(1), clock));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoginRateLimiter(1, 1, 2, Duration.ZERO, clock));
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
