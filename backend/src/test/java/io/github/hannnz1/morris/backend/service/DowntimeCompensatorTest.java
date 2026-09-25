package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DowntimeCompensatorTest extends PostgresIntegrationTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class ClockOverride {
        @Bean @Primary
        Clock testClock() { return MutableClock.at(BASE_INSTANT); }
    }

    @Autowired private GameSessionService gameSessions;
    @Autowired private GameSessionRepository games;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TokenService tokens;
    @Autowired private DowntimeCompensator compensator;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aSixtySecondGapShiftsEveryActiveGamesDeadlineForward() {
        // Reset to a known absolute instant first: the Spring context (and this MutableClock bean)
        // is shared across test methods in this class, so without resetting, this test's outcome
        // would depend on how far a previously-run method already advanced the clock.
        ((MutableClock) clock).set(BASE_INSTANT);
        var game = startedGame("5+3");
        GameSessionEntity before = games.findById(game.id()).orElseThrow();
        Instant deadlineBefore = before.getTurnDeadlineAt();
        Instant turnStartedBefore = before.getTurnStartedAt();

        jdbc.update("update system_heartbeat set last_alive_at = ? where id = 1",
                java.sql.Timestamp.from(BASE_INSTANT));
        ((MutableClock) clock).advance(Duration.ofSeconds(60));

        compensator.compensateOnStartup();

        GameSessionEntity after = games.findById(game.id()).orElseThrow();
        assertThat(after.getTurnDeadlineAt()).isEqualTo(deadlineBefore.plusSeconds(60));
        assertThat(after.getTurnStartedAt()).isEqualTo(turnStartedBefore.plusSeconds(60));
    }

    @Test
    void aFiveSecondGapDoesNotCompensateAtAll() {
        ((MutableClock) clock).set(BASE_INSTANT);
        var game = startedGame("5+3");
        Instant deadlineBefore = games.findById(game.id()).orElseThrow().getTurnDeadlineAt();

        jdbc.update("update system_heartbeat set last_alive_at = ? where id = 1",
                java.sql.Timestamp.from(BASE_INSTANT));
        ((MutableClock) clock).advance(Duration.ofSeconds(5));

        compensator.compensateOnStartup();

        assertThat(games.findById(game.id()).orElseThrow().getTurnDeadlineAt()).isEqualTo(deadlineBefore);
    }

    private GameResponse startedGame(String timeControl) {
        String rawWhiteToken = tokens.generate();
        Instant now = Instant.now();
        PlayerEntity white = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(rawWhiteToken), "HUMAN", now, now));
        var created = gameSessions.createForPlayer(white, "create-" + UUID.randomUUID(), timeControl);

        String rawBlackToken = tokens.generate();
        PlayerEntity black = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Zhu",
                tokens.hash(rawBlackToken), "HUMAN", now, now));
        var joined = gameSessions.joinByBearer(created.game().id(), black, "join-" + UUID.randomUUID());
        return joined.game();
    }
}
