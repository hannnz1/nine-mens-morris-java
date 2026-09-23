package io.github.hannnz1.morris.backend.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** In-memory fixed-window counter. Accurate for a single instance; see docs/development-plan-v2 3.5. */
@Component
public class RateLimiter {

    private record Window(Instant start, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    public RateLimiter() {
        this(Instant::now);
    }

    public RateLimiter(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.get();
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || Duration.between(existing.start(), now).compareTo(window) >= 0) {
                return new Window(now, new AtomicInteger(0));
            }
            return existing;
        });
        return current.count().incrementAndGet() <= limit;
    }
}
