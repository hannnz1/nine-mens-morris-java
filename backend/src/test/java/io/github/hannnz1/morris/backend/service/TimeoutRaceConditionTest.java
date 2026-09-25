package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.ActionType;
import io.github.hannnz1.morris.engine.BoardPosition;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec M2.9: the scanner and a live action racing for the same lock must never both "win". */
class TimeoutRaceConditionTest extends PostgresIntegrationTest {

    // Measured (not assumed) by the two solo tests below: whichever racer actually performs the
    // finish, GameFinisher.finish is the only place that mutates and saves the entity on that path
    // (neither the scanner's finishOneIfStillTimedOut nor performAction's on-arrival
    // rejectedByTimeout branch calls entity.updateState/saveAndFlush before delegating to it), so a
    // single finish - via either path - advances the JPA @Version by exactly this much.
    private static final long SINGLE_FINISH_VERSION_DELTA = 1L;

    @TestConfiguration
    static class ClockOverride {
        @Bean @Primary
        Clock testClock() { return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z")); }
    }

    @Autowired private GameSessionService gameSessions;
    @Autowired private GameSessionRepository games;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TokenService tokens;
    @Autowired private TimeoutScanner scanner;
    @Autowired private Clock clock;

    private String whiteToken;
    private String blackToken;

    // Calibration test (not raced): proves SINGLE_FINISH_VERSION_DELTA for the scanner-only path,
    // rather than that constant being asserted on faith in the race test below.
    @Test
    void aSoloScannerFinishAdvancesVersionByExactlyOne() {
        var game = startedGame("3+2");
        game = playBothFirstMoves(game);
        long versionBefore = games.findById(game.id()).orElseThrow().getVersion();
        ((MutableClock) clock).advance(Duration.ofSeconds(181));

        scanner.scanOnce();

        GameSessionEntity after = games.findById(game.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLACK_WON");
        assertThat(after.getResultReason()).isEqualTo("TIMEOUT");
        assertThat(after.getVersion() - versionBefore).isEqualTo(SINGLE_FINISH_VERSION_DELTA);
    }

    // Calibration test (not raced): proves SINGLE_FINISH_VERSION_DELTA for the on-arrival
    // rejectedByTimeout path in performAction, the other possible winner of the race below.
    @Test
    void aSoloActionTimeoutRejectionAdvancesVersionByExactlyOne() {
        var game = startedGame("3+2");
        game = playBothFirstMoves(game);
        long versionBefore = games.findById(game.id()).orElseThrow().getVersion();
        ((MutableClock) clock).advance(Duration.ofSeconds(181));

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "solo-timeout-" + UUID.randomUUID(),
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, game.version()));

        assertThat(outcome.rejectedByTimeout()).isTrue();
        GameSessionEntity after = games.findById(game.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLACK_WON");
        assertThat(after.getResultReason()).isEqualTo("TIMEOUT");
        assertThat(after.getVersion() - versionBefore).isEqualTo(SINGLE_FINISH_VERSION_DELTA);
    }

    // Both first moves are played first (per the controller's ruling #4) so the racing action is a
    // NON-first move: expiring it must be a genuine TIMEOUT loss, not the first-move ABORTED wash,
    // so the invariant below (BLACK_WON/TIMEOUT) is unambiguous regardless of which racer wins.
    @RepeatedTest(20)
    void theScanAndAConcurrentActionNeverBothSucceed() throws Exception {
        var game = startedGame("3+2");
        game = playBothFirstMoves(game);
        final long expectedVersion = game.version();
        final long versionBefore = games.findById(game.id()).orElseThrow().getVersion();
        final UUID gameId = game.id();
        ((MutableClock) clock).advance(Duration.ofSeconds(181)); // already timed out for both racers

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var actionResult = pool.submit(() -> {
            start.await();
            try {
                return gameSessions.performAction(gameId, whiteToken, null, "race-key-" + UUID.randomUUID(),
                        new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, expectedVersion));
            } catch (ApiException exception) {
                return exception; // GAME_NOT_ACTIVE is an acceptable, expected outcome here
            }
        });
        var scanResult = pool.submit(() -> {
            start.await();
            scanner.scanOnce();
            return null;
        });
        start.countDown();
        Object actionOutcome = actionResult.get(10, TimeUnit.SECONDS);
        scanResult.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // The action side must have ended in exactly one of the two outcomes the spec allows for
        // racing a live action against the scanner - anything else (a lock timeout, an NPE, a stale
        // VERSION_CONFLICT) fails the test outright instead of being silently swallowed.
        if (actionOutcome instanceof ActionOutcome outcome) {
            assertThat(outcome.rejectedByTimeout())
                    .as("a non-exceptional action outcome in this race must be a timeout rejection")
                    .isTrue();
        } else if (actionOutcome instanceof ApiException apiException) {
            assertThat(apiException.code())
                    .as("an exceptional action outcome in this race must be GAME_NOT_ACTIVE (the scan won the lock first)")
                    .isEqualTo("GAME_NOT_ACTIVE");
        } else {
            throw new AssertionError("Unexpected action outcome: " + actionOutcome);
        }

        // Whichever raced first, the game ends up finished exactly once, with a consistent result -
        // never IN_PROGRESS (the move silently "succeeding" despite being past deadline) and never
        // double-processed. "Exactly once" is proven by the version delta, not just by the final
        // status/reason: a double-finish (both racers writing) would show up as a delta of 2x
        // SINGLE_FINISH_VERSION_DELTA, which this assertion would catch.
        var finalState = games.findById(gameId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo("BLACK_WON");
        assertThat(finalState.getResultReason()).isEqualTo("TIMEOUT");
        assertThat(finalState.getVersion() - versionBefore).isEqualTo(SINGLE_FINISH_VERSION_DELTA);
    }

    private GameResponse playBothFirstMoves(GameResponse game) {
        var afterWhite = gameSessions.performAction(game.id(), whiteToken, null, "race-setup-1-" + UUID.randomUUID(),
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlack = gameSessions.performAction(game.id(), blackToken, null, "race-setup-2-" + UUID.randomUUID(),
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhite.game().version()));
        return afterBlack.game();
    }

    private GameResponse startedGame(String timeControl) {
        String rawWhiteToken = tokens.generate();
        Instant now = Instant.now();
        PlayerEntity white = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(rawWhiteToken), "HUMAN", now, now));
        this.whiteToken = rawWhiteToken;
        var created = gameSessions.createForPlayer(white, "create-" + UUID.randomUUID(), timeControl);

        String rawBlackToken = tokens.generate();
        PlayerEntity black = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Zhu",
                tokens.hash(rawBlackToken), "HUMAN", now, now));
        this.blackToken = rawBlackToken;
        var joined = gameSessions.joinByBearer(created.game().id(), black, "join-" + UUID.randomUUID());
        return joined.game();
    }
}
