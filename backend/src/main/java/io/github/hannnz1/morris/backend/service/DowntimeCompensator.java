package io.github.hannnz1.morris.backend.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup downtime compensation, per spec M2.3/M2.9: a single-row {@code system_heartbeat} table
 * records the last instant this process was known to be alive. On startup, the gap between that
 * timestamp and now is assumed to be downtime (the process was not running, not merely idle) and is
 * pushed onto every currently IN_PROGRESS game's clock, so no one's turn silently expires purely
 * because the server was down. A running {@link #heartbeat()} then keeps the row current so a later
 * restart measures only the real gap.
 *
 * <p>{@link TimeoutScanner} gates its scheduled sweep on {@link #ready()} so it can never race this
 * one-time compensation: a game must not be timed out for downtime that hasn't been compensated yet.
 */
@Component
public class DowntimeCompensator {

    private static final Duration COMPENSATION_THRESHOLD = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public DowntimeCompensator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean ready() {
        return ready.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void compensateOnStartup() {
        Instant lastAlive = jdbc.queryForObject(
                "select last_alive_at from system_heartbeat where id = 1", Timestamp.class).toInstant();
        Instant now = clock.instant();
        Duration downtime = Duration.between(lastAlive, now);
        if (downtime.compareTo(COMPENSATION_THRESHOLD) > 0) {
            jdbc.update("""
                    update game_sessions
                       set turn_started_at  = turn_started_at  + (? * interval '1 millisecond'),
                           turn_deadline_at = turn_deadline_at + (? * interval '1 millisecond')
                     where status = 'IN_PROGRESS'
                    """, downtime.toMillis(), downtime.toMillis());
        }
        jdbc.update("update system_heartbeat set last_alive_at = ? where id = 1", Timestamp.from(now));
        ready.set(true);
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        if (!ready.get()) {
            return; // don't overwrite the pre-compensation baseline with a live heartbeat mid-startup
        }
        jdbc.update("update system_heartbeat set last_alive_at = ? where id = 1", Timestamp.from(clock.instant()));
    }
}
