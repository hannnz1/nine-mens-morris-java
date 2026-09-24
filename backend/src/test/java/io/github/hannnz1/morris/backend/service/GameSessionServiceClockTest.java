package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GameSessionServiceClockTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired
    private GameSessionService gameSessions;
    @Autowired
    private Clock clock;

    @Test
    void createStampsCreatedAtFromTheInjectedClock() {
        var response = gameSessions.create(new CreateGameRequest("Han"));

        assertThat(response.game().createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

        ((MutableClock) clock).advance(java.time.Duration.ofMinutes(5));
        var second = gameSessions.create(new CreateGameRequest("Han2"));
        assertThat(second.game().createdAt()).isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
    }
}
