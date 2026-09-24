package io.github.hannnz1.morris.backend.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * For that guarantee to hold, {@link #ready()} must not flip true until the compensating shift (and
 * the heartbeat write) have actually committed - not merely been executed inside a transaction that
 * might still roll back - so {@code ready.set(true)} runs via {@link TransactionTemplate} rather than
 * a declarative {@code @Transactional} on this method, strictly after the transaction returns
 * (equivalently, after commit); if the transaction throws, {@code ready} is left false and the
 * exception propagates - failing startup loudly rather than silently enabling the scanner early.
 */
@Component
public class DowntimeCompensator {

    private static final Duration COMPENSATION_THRESHOLD = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public DowntimeCompensator(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public boolean ready() {
        return ready.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        transactionTemplate.executeWithoutResult(status -> {
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
        });
        // Only reached once the transaction above has committed (executeWithoutResult propagates
        // any exception without returning), so a concurrent TimeoutScanner tick can never observe
        // ready() == true while the compensating shift is still uncommitted or was rolled back.
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
