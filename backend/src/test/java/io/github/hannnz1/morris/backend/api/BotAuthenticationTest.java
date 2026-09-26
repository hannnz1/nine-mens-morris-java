package io.github.hannnz1.morris.backend.api;
import io.github.hannnz1.morris.backend.persistence.*;
import io.github.hannnz1.morris.backend.service.*;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class BotAuthenticationTest extends PostgresIntegrationTest {
    @Autowired PlayerRepository repository;
    @Autowired PlayerService players;
    @Autowired SeatResolver seats;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Test void migrationCreatesThreeDistinctUnusableBotCredentials() {
        var hashes = jdbc.queryForList("select token_hash from players where id in ('00000000-0000-4000-8000-00000000b001','00000000-0000-4000-8000-00000000b002','00000000-0000-4000-8000-00000000b003') and kind='BOT'", String.class);
        assertThat(hashes).hasSize(3).doesNotHaveDuplicates();
        for (var h : hashes) assertThat(h).hasSize(64).doesNotMatch("[0-9a-f]{64}");
    }
    @Test void botCannotAuthenticateEvenWhenItsHashMatchesARealToken() {
        String token = tokens.generate();
        var bot = repository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "TestBot", tokens.hash(token), "BOT", Instant.now(), Instant.now()));
        try {
            assertThatThrownBy(() -> players.requirePlayer(token)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> players.createOrGet(new CreatePlayerRequest("Someone", token))).isInstanceOf(ApiException.class);
            var game = new GameSessionEntity(UUID.randomUUID(), "TestBot", null, null, null, "WAITING_FOR_PLAYER", "{}", Instant.now());
            game.assignPlayers(bot.getId(), null);
            assertThatThrownBy(() -> seats.resolve(game, token, null)).isInstanceOf(ApiException.class);
        } finally { repository.deleteById(bot.getId()); }
    }
}
