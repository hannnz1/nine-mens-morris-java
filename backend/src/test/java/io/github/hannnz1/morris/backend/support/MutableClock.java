package io.github.hannnz1.morris.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A settable clock for tests: never sleeps, advances only when the test tells it to. */
public final class MutableClock extends Clock {
    private final ZoneId zone;
    private volatile Instant now;

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static MutableClock at(Instant start) {
        return new MutableClock(start, ZoneOffset.UTC);
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
