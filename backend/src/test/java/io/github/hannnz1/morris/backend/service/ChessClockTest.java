package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessClockTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired private GameSessionService gameSessions;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TokenService tokens;
    @Autowired private Clock clock;

    private String whiteToken;

    @Test
    void rejectsAnUnsupportedTimeControl() {
        PlayerEntity white = createPlayer("Han");
        assertThatThrownBy(() -> gameSessions.createForPlayer(white, "k1", "1+0"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
    }

    @Test
    void clockStartsWhenBlackJoinsNotWhenWhiteCreates() {
        PlayerEntity white = createPlayer("Han");
        var created = gameSessions.createForPlayer(white, "k1", "5+3");
        assertThat(created.game().clock().running()).isFalse();

        PlayerEntity black = createPlayer("Zhu");
        var joined = gameSessions.joinByBearer(created.game().id(), black, "k2");
        assertThat(joined.game().clock().running()).isTrue();
        assertThat(joined.game().clock().whiteMs()).isEqualTo(5 * 60_000L);
        assertThat(joined.game().clock().blackMs()).isEqualTo(5 * 60_000L);
    }

    @Test
    void placingAMoveDeductsElapsedTimeAndAddsIncrementOnTurnHandoff() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofSeconds(10));

        var response = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        // White used 10s of a 300s budget, then gains the 3s increment on handing the turn over:
        // 300_000 - 10_000 + 3_000 = 293_000.
        assertThat(response.clock().whiteMs()).isEqualTo(293_000L);
        assertThat(response.clock().blackMs()).isEqualTo(300_000L);
    }

    @Test
    void aMoveArrivingExactlyWhenTimeReachesZeroIsRejectedAsATimeout() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofMillis(300_000)); // exactly the full budget, not a millisecond less

        var response = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        assertThat(response.status()).isEqualTo("BLACK_WON");
    }

    private PlayerEntity createPlayer(String nickname) {
        Instant now = Instant.now();
        return playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), nickname,
                tokens.hash(tokens.generate()), "HUMAN", now, now));
    }

    private GameResponse startedGame(String timeControl) {
        String rawWhiteToken = tokens.generate();
        Instant now = Instant.now();
        PlayerEntity white = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(rawWhiteToken), "HUMAN", now, now));
        this.whiteToken = rawWhiteToken;
        var created = gameSessions.createForPlayer(white, "create-" + UUID.randomUUID(), timeControl);

        PlayerEntity black = createPlayer("Zhu");
        var joined = gameSessions.joinByBearer(created.game().id(), black, "join-" + UUID.randomUUID());
        return joined.game();
    }
}
