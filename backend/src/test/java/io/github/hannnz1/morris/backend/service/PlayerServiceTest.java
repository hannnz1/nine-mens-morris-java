package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerServiceTest extends io.github.hannnz1.morris.backend.support.PostgresIntegrationTest {

    @Autowired
    private PlayerService playerService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private TokenService tokens;

    @Test
    void createIsIdempotentByClientToken() {
        String token = tokens.generate();
        var first = playerService.createOrGet(new CreatePlayerRequest("Han", token));
        var second = playerService.createOrGet(new CreatePlayerRequest("Han", token));

        assertThat(second.playerId()).isEqualTo(first.playerId());
        assertThat(players.findByTokenHash(tokens.hash(token)))
                .hasValueSatisfying(entity -> assertThat(entity.getId()).isEqualTo(first.playerId()));
    }

    @Test
    void rejectsAnEmptyNickname() {
        assertThatThrownBy(() -> playerService.createOrGet(new CreatePlayerRequest("   ", tokens.generate())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
    }

    @Test
    void rejectsANicknameOnTheBlockList() {
        assertThatThrownBy(() -> playerService.createOrGet(new CreatePlayerRequest("admin", tokens.generate())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "NICKNAME_NOT_ALLOWED");
    }

    @Test
    void getByTokenFailsForAnUnknownBearerToken() {
        assertThrows(ApiException.class, () -> playerService.getByToken(tokens.generate()));
    }
}
