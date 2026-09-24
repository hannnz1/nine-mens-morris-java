package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.engine.GameResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Background "Pattern B" timeout sweep, per spec M2.3/M2.9: select candidate games without locking
 * (cheap, no contention with live traffic), then for each candidate open its own transaction, take
 * the pessimistic row lock, re-check that it is still eligible (another writer - typically a live
 * action arriving right at the deadline via {@link GameSessionService#performAction} - may have
 * already finished or moved it), and only then call {@link GameFinisher#finish}.
 *
 * <p>Deliberately does not put {@code @Transactional} on the per-id worker method and call it from
 * {@link #scanOnce()} in the same bean: that self-invocation would bypass Spring's proxy, so
 * {@link GameSessionRepository#findByIdForUpdate} (a {@code PESSIMISTIC_WRITE} query) would silently
 * run with no transaction at all. {@link TransactionTemplate} gives one transaction per candidate id
 * without that trap.
 *
 * <p>Whether an expired game ends ABORTED or TIMEOUT is decided in exactly one place,
 * {@link ClockTimeoutOutcome#forExpiry}, shared with the on-arrival path in
 * {@link GameSessionService#performAction} - this scanner never re-derives that rule itself.
 */
@Component
public class TimeoutScanner {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TimeoutScanner.class);

    private final GameSessionRepository games;
    private final GameFinisher finisher;
    private final GameSessionService gameSessions;
    private final Clock clock;
    private final DowntimeCompensator compensator;
    private final TransactionTemplate transactionTemplate;

    public TimeoutScanner(GameSessionRepository games, GameFinisher finisher, GameSessionService gameSessions,
                           Clock clock, DowntimeCompensator compensator, PlatformTransactionManager transactionManager) {
        this.games = games;
        this.finisher = finisher;
        this.gameSessions = gameSessions;
        this.clock = clock;
        this.compensator = compensator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 1000)
    public void scan() {
        if (!compensator.ready()) {
            return; // startup downtime compensation hasn't run yet - see DowntimeCompensator
        }
        scanOnce();
    }

    // Package-visible: called directly by tests to avoid sleeping through the real @Scheduled
    // interval, and by scan() above once startup compensation has completed.
    void scanOnce() {
        List<UUID> candidateIds = games.findTimedOutCandidateIds(clock.instant());
        for (UUID id : candidateIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> finishOneIfStillTimedOut(id));
            } catch (RuntimeException exception) {
                LOGGER.warn("Timeout scan failed for game {}", id, exception);
            }
        }
    }

    private void finishOneIfStillTimedOut(UUID id) {
        GameSessionEntity entity = games.findByIdForUpdate(id).orElse(null);
        if (entity == null || !"IN_PROGRESS".equals(entity.getStatus()) || entity.getTurnDeadlineAt() == null) {
            return; // already finished (or never had a clock) by the time this id's turn came up
        }
        Instant now = clock.instant();
        if (now.isBefore(entity.getTurnDeadlineAt())) {
            return; // the deadline moved (e.g. downtime compensation) since this id was selected
        }
        GameResult expiry = ClockTimeoutOutcome.forExpiry(gameSessions.stateOf(entity));
        finisher.finish(entity, expiry.winner(), expiry.reason());
    }
}
