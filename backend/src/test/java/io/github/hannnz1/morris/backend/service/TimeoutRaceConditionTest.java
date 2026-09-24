package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.ActionType;
import io.github.hannnz1.morris.engine.BoardPosition;
import org.junit.jupiter.api.RepeatedTest;
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

    // Both first moves are played first (per the controller's ruling #4) so the racing action is a
    // NON-first move: expiring it must be a genuine TIMEOUT loss, not the first-move ABORTED wash,
    // so the invariant below (BLACK_WON/TIMEOUT) is unambiguous regardless of which racer wins.
    @RepeatedTest(20)
    void theScanAndAConcurrentActionNeverBothSucceed() throws Exception {
        var game = startedGame("3+2");
        game = playBothFirstMoves(game);
        final long expectedVersion = game.version();
        final UUID gameId = game.id();
        ((MutableClock) clock).advance(Duration.ofSeconds(181)); // already timed out for both racers

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var actionResult = pool.submit(() -> {
            start.await();
            try {
                return gameSessions.performAction(gameId, whiteToken, null, "race-key-" + UUID.randomUUID(),
                        new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, expectedVersion));
            } catch (RuntimeException exception) {
                return exception; // GAME_NOT_ACTIVE is an acceptable, expected outcome here
            }
        });
        var scanResult = pool.submit(() -> {
            start.await();
            scanner.scanOnce();
            return null;
        });
        start.countDown();
        actionResult.get(10, TimeUnit.SECONDS);
        scanResult.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Whichever raced first, the game ends up finished exactly once, with a consistent result -
        // never IN_PROGRESS (the move silently "succeeding" despite being past deadline) and never
        // double-processed (which would show up as a version mismatch or a duplicate broadcast).
        var finalState = games.findById(gameId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo("BLACK_WON");
        assertThat(finalState.getResultReason()).isEqualTo("TIMEOUT");
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
