package io.github.hannnz1.morris.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitWithinAWindowThenBlocks() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock::get);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("ip:1.2.3.4", 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void resetsAfterTheWindowElapses() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock::get);

        assertThat(limiter.tryAcquire("ip:1.2.3.4", 1, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 1, Duration.ofMinutes(1))).isFalse();

        clock.set(clock.get().plus(Duration.ofMinutes(1).plusSeconds(1)));
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 1, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void tracksDifferentKeysIndependently() {
        RateLimiter limiter = new RateLimiter(Instant::now);
        assertThat(limiter.tryAcquire("ip:1.1.1.1", 1, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("ip:2.2.2.2", 1, Duration.ofMinutes(1))).isTrue();
    }
}
