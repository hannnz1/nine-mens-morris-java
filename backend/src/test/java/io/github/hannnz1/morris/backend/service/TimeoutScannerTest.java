package io.github.hannnz1.morris.backend.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutScannerTest extends PostgresIntegrationTest {

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

    // Both first moves are played (Task 5's first-move grace applies only to a side's own first
    // move) so that advancing the clock past the main budget times out a NON-first move, per the
    // controller's ruling #4: a fixture with no moves played times out ABORTED, not TIMEOUT.
    @Test
    void aGameWhoseDeadlineHasPassedIsFinishedByTheNextScan() {
        var game = startedGame("3+2");
        game = playBothFirstMoves(game);
        ((MutableClock) clock).advance(Duration.ofSeconds(181)); // 3-minute main budget, well past it

        scanner.scanOnce();

        GameSessionEntity reloaded = games.findById(game.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("BLACK_WON");
        assertThat(reloaded.getResultReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void aGameWithinItsDeadlineIsUntouchedByTheScan() {
        var game = startedGame("10+5");
        ((MutableClock) clock).advance(Duration.ofSeconds(5));

        scanner.scanOnce();

        assertThat(games.findById(game.id()).orElseThrow().getStatus()).isEqualTo("IN_PROGRESS");
    }

    // Regression for spec M2.3's first-move grace: nobody has moved yet, so the side to move
    // (White) is still on its first move when the 30s grace expires - ABORTED, winner null, not a
    // TIMEOUT loss for White.
    @Test
    void aFirstMoveTimeoutIsAbortedNotTimedOut() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofSeconds(31)); // past the 30s first-move grace

        scanner.scanOnce();

        GameSessionEntity reloaded = games.findById(game.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("ABORTED");
        assertThat(reloaded.getResultWinner()).isNull();
    }

    private GameResponse playBothFirstMoves(GameResponse game) {
        var afterWhite = gameSessions.performAction(game.id(), whiteToken, null, "scan-act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlack = gameSessions.performAction(game.id(), blackToken, null, "scan-act-2",
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
