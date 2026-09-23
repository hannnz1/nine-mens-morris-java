package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Regression for M1 final whole-branch review C2: GameSessionRepository's derived query
// countByWhitePlayerIdOrBlackPlayerIdAndStatusIn actually means "white = ? OR (black = ? AND
// status IN ?)" (Spring Data binds And tighter than Or), not the intended "(white = ? OR
// black = ?) AND status IN ?" - any player who had ever created 5 games, regardless of status,
// was permanently locked out of creating a 6th. Replaced with an explicit @Query
// (countActiveGamesForPlayer); these tests exercise both the fixed-bug case and the still-correct
// blocking case through the real createForPlayer path.
class GameSessionServiceActiveGameLimitTest extends io.github.hannnz1.morris.backend.support.PostgresIntegrationTest {

    @Autowired
    private GameSessionService gameSessions;
    @Autowired
    private GameSessionRepository games;
    @Autowired
    private PlayerRepository players;
    @Autowired
    private TokenService tokens;

    private PlayerEntity newPlayer() {
        return players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(tokens.generate()), "HUMAN", Instant.now(), Instant.now()));
    }

    @Test
    void aSixthGameSucceedsOnceAllFiveEarlierGamesAreFinished() {
        PlayerEntity player = newPlayer();
        for (int i = 0; i < 5; i++) {
            var response = gameSessions.createForPlayer(player, "finished-limit-" + i);
            GameSessionEntity entity = games.findById(response.game().id()).orElseThrow();
            entity.updateState("WHITE_WON", entity.getStateJson(), Instant.now());
            games.saveAndFlush(entity);
        }

        assertThatCode(() -> gameSessions.createForPlayer(player, "finished-limit-sixth"))
                .doesNotThrowAnyException();
    }

    @Test
    void aSixthGameIsStillBlockedWhileFiveGamesAreGenuinelyActive() {
        PlayerEntity player = newPlayer();
        for (int i = 0; i < 5; i++) {
            gameSessions.createForPlayer(player, "active-limit-" + i);
        }

        assertThatThrownBy(() -> gameSessions.createForPlayer(player, "active-limit-sixth"))
                .isInstanceOf(io.github.hannnz1.morris.backend.api.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "TOO_MANY_ACTIVE_GAMES");
    }

    @Test
    void countsGamesWhereThePlayerIsWhiteOrBlackButOnlyMatchingStatuses() {
        PlayerEntity player = newPlayer();
        long before = games.countActiveGamesForPlayer(player.getId(),
                java.util.List.of("WAITING_FOR_PLAYER", "IN_PROGRESS"));
        assertThat(before).isZero();

        var response = gameSessions.createForPlayer(player, "count-check-1");
        assertThat(games.countActiveGamesForPlayer(player.getId(),
                java.util.List.of("WAITING_FOR_PLAYER", "IN_PROGRESS"))).isEqualTo(1);

        GameSessionEntity entity = games.findById(response.game().id()).orElseThrow();
        entity.updateState("WHITE_WON", entity.getStateJson(), Instant.now());
        games.saveAndFlush(entity);
        assertThat(games.countActiveGamesForPlayer(player.getId(),
                java.util.List.of("WAITING_FOR_PLAYER", "IN_PROGRESS"))).isZero();
    }
}
