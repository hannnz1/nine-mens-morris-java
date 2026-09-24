package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Regression/acceptance test for the M2.3 HTTP-level ruling: a move that arrives after the mover's
// own clock has already reached zero must come back as an HTTP 409 GAME_NOT_ACTIVE, not a plain 200
// with the finished game embedded in the body - even though GameSessionService.performAction itself
// returns an ActionOutcome rather than throwing (see GameController.action). Modeled on
// BearerCreatedGameFlowIntegrationTest's real end-to-end HTTP flow (create/join/act via
// TestRestTemplate), with ChessClockTest's MutableClock-override pattern for pushing the clock past
// the deadline without sleeping.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClockTimeoutHttpIntegrationTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @LocalServerPort
    private int port;
    @Autowired
    private Clock clock;

    @Test
    void aMoveAfterTheClockExpiresIs409OnFirstAttemptAndOnIdempotentRetry() {
        String whiteToken = "w".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Han", "clientToken", whiteToken), Map.class);
        String blackToken = "b".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Zhu", "clientToken", blackToken), Map.class);

        HttpHeaders createHeaders = bearer(whiteToken);
        createHeaders.set("Idempotency-Key", "timeout-create");
        var createResponse = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of("timeControl", "5+3"), createHeaders), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object gameId = ((Map<?, ?>) createResponse.getBody().get("game")).get("id");

        HttpHeaders joinHeaders = bearer(blackToken);
        joinHeaders.set("Idempotency-Key", "timeout-join");
        var joinResponse = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), joinHeaders), Map.class);
        assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long version = ((Number) ((Map<?, ?>) joinResponse.getBody().get("game")).get("version")).longValue();

        // Push the injected clock well past the mover's deadline without sleeping.
        ((MutableClock) clock).advance(Duration.ofMillis(300_000));

        HttpHeaders actionHeaders = bearer(whiteToken);
        actionHeaders.set("Idempotency-Key", "timeout-act-1");
        var firstAttempt = rest.exchange(url("/api/v1/games/" + gameId + "/actions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "PLACE", "to", "A1", "expectedVersion", version), actionHeaders),
                Map.class);
        assertThat(firstAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(firstAttempt.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");

        // A retry with the SAME Idempotency-Key must replay the same 409, not a 200 - the stored
        // idempotency record must carry the rejectedByTimeout outcome, not just the finished game.
        var retry = rest.exchange(url("/api/v1/games/" + gameId + "/actions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "PLACE", "to", "A1", "expectedVersion", version), actionHeaders),
                Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
