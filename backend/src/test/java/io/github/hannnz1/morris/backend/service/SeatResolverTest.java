package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.engine.Player;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatResolverTest extends io.github.hannnz1.morris.backend.support.PostgresIntegrationTest {

    @Autowired
    private SeatResolver resolver;
    @Autowired
    private TokenService tokens;
    @Autowired
    private PlayerRepository players;

    private GameSessionEntity gameWithLegacySeats(String whiteToken, String blackToken) {
        Instant now = Instant.now();
        return new GameSessionEntity(UUID.randomUUID(), "Alice", "Bob",
                tokens.hash(whiteToken), tokens.hash(blackToken), "IN_PROGRESS",
                writeMinimalState(), now);
    }

    private String writeMinimalState() {
        return "{}"; // SeatResolver never reads state_json; a real GameState isn't needed here.
    }

    @Test
    void resolvesTheLegacySeatToken() {
        String whiteToken = tokens.generate();
        GameSessionEntity game = gameWithLegacySeats(whiteToken, tokens.generate());

        assertThat(resolver.resolve(game, null, whiteToken)).isEqualTo(Player.WHITE);
    }

    @Test
    void resolvesABearerPlayerToken() {
        String bearerToken = tokens.generate();
        PlayerEntity player = players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(bearerToken), "HUMAN", Instant.now(), Instant.now()));
        GameSessionEntity game = gameWithLegacySeats(tokens.generate(), tokens.generate());
        game.assignPlayers(player.getId(), null);

        assertThat(resolver.resolve(game, bearerToken, null)).isEqualTo(Player.WHITE);
    }

    @Test
    void rejectsAnUnknownToken() {
        GameSessionEntity game = gameWithLegacySeats(tokens.generate(), tokens.generate());
        assertThatThrownBy(() -> resolver.resolve(game, null, tokens.generate()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PLAYER_TOKEN");
    }

    @Test
    void rejectsWhenNeitherHeaderIsPresent() {
        GameSessionEntity game = gameWithLegacySeats(tokens.generate(), tokens.generate());
        assertThatThrownBy(() -> resolver.resolve(game, null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "AUTH_REQUIRED");
    }

    // Regression for M1 final whole-branch review C1/I1: a Bearer/identity-created game
    // (GameSessionService.createForPlayer) stores null white_token_hash/black_token_hash - every
    // prior test in this file built games with gameWithLegacySeats, which always supplies non-null
    // hashes, so this exact gap (matchLegacyHash calling TokenService.matches against a null hash)
    // went unnoticed. Must resolve to a clean ApiException, not throw an NPE.
    @Test
    void rejectsALegacyTokenAgainstABearerCreatedGameInsteadOfThrowing() {
        UUID whitePlayerId = UUID.randomUUID();
        GameSessionEntity game = new GameSessionEntity(UUID.randomUUID(), "Alice", null,
                null, null, "WAITING_FOR_PLAYER", "{}", Instant.now());
        game.assignPlayers(whitePlayerId, null);

        assertThatThrownBy(() -> resolver.resolve(game, null, tokens.generate()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PLAYER_TOKEN");
    }

    @Test
    void rejectsABearerTokenBelongingToADifferentPlayer() {
        String ownerToken = tokens.generate();
        PlayerEntity owner = players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(ownerToken), "HUMAN", Instant.now(), Instant.now()));
        String strangerToken = tokens.generate();
        players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Stranger",
                tokens.hash(strangerToken), "HUMAN", Instant.now(), Instant.now()));

        GameSessionEntity game = new GameSessionEntity(UUID.randomUUID(), "Han", null,
                null, null, "WAITING_FOR_PLAYER", "{}", Instant.now());
        game.assignPlayers(owner.getId(), null);

        assertThatThrownBy(() -> resolver.resolve(game, strangerToken, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PLAYER_TOKEN");
    }

    @Test
    void rejectsWhenBothBearerAndLegacyCredentialsAreWrong() {
        GameSessionEntity game = gameWithLegacySeats(tokens.generate(), tokens.generate());
        assertThatThrownBy(() -> resolver.resolve(game, tokens.generate(), tokens.generate()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PLAYER_TOKEN");
    }
}
