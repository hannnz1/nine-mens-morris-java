package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// PostgresIntegrationTest's @SpringBootTest defaults to WebEnvironment.MOCK, which doesn't start
// an embedded server or expose a TestRestTemplate/@LocalServerPort. Redeclaring @SpringBootTest
// here (Spring resolves the closest one in the class hierarchy) switches just this test class to
// RANDOM_PORT, matching the pattern established by PlayerApiIntegrationTest (Task 6).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomCodeIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @LocalServerPort
    private int port;

    private String createPlayer(String nickname) {
        var response = rest.postForEntity(url("/api/v1/players"),
                Map.of("nickname", nickname, "clientToken", UUID.randomUUID().toString().repeat(2).substring(0, 43)),
                Map.class);
        return null; // placeholder, replaced below with the real client token used to authenticate
    }

    @Test
    void createReturnsARoomCodeAndJoinByCodeSeatsTheSecondPlayer() {
        String whiteClientToken = "w".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Alice", "clientToken", whiteClientToken), Map.class);

        HttpHeaders createHeaders = new HttpHeaders();
        createHeaders.setBearerAuth(whiteClientToken);
        createHeaders.set("Idempotency-Key", "create-1");
        createHeaders.setContentType(MediaType.APPLICATION_JSON);
        var createResponse = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), createHeaders), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String roomCode = (String) createResponse.getBody().get("roomCode");
        assertThat(roomCode).matches("\\d{6}");

        var lookupResponse = rest.getForEntity(url("/api/v1/rooms/" + roomCode), Map.class);
        assertThat(lookupResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookupResponse.getBody().get("status")).isEqualTo("WAITING_FOR_PLAYER");

        String blackClientToken = "b".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Bob", "clientToken", blackClientToken), Map.class);
        Object gameId = ((Map<?, ?>) createResponse.getBody().get("game")).get("id");

        HttpHeaders joinHeaders = new HttpHeaders();
        joinHeaders.setBearerAuth(blackClientToken);
        joinHeaders.set("Idempotency-Key", "join-1");
        joinHeaders.setContentType(MediaType.APPLICATION_JSON);
        var joinResponse = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), joinHeaders), Map.class);
        assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatorCannotJoinTheirOwnGameAsBlack() {
        String clientToken = "c".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Alice", "clientToken", clientToken), Map.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken);
        headers.set("Idempotency-Key", "create-2");
        headers.setContentType(MediaType.APPLICATION_JSON);
        var createResponse = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), Map.class);
        Object gameId = ((Map<?, ?>) createResponse.getBody().get("game")).get("id");

        headers.set("Idempotency-Key", "join-2");
        var joinResponse = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), Map.class);
        assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(joinResponse.getBody().get("code")).isEqualTo("CANNOT_JOIN_OWN_GAME");
    }

    // Regression for M1 final whole-branch review C1: createForPlayer stores null
    // white_token_hash/black_token_hash, and the anonymous (legacy, body-based) join path used to
    // call TokenService.matches against that null hash directly, throwing an NPE -> 500. This is
    // exactly the pre-M1 "copy invite link" flow, still reachable whenever a tab has no saved
    // player identity. Must now return a clean 4xx, never a 500.
    @Test
    void anonymousJoinAgainstABearerCreatedGameFailsCleanlyInsteadOfCrashing() {
        String whiteClientToken = "q".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Alice", "clientToken", whiteClientToken), Map.class);

        HttpHeaders createHeaders = new HttpHeaders();
        createHeaders.setBearerAuth(whiteClientToken);
        createHeaders.set("Idempotency-Key", "anon-join-regression-create");
        createHeaders.setContentType(MediaType.APPLICATION_JSON);
        var createResponse = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), createHeaders), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object gameId = ((Map<?, ?>) createResponse.getBody().get("game")).get("id");

        HttpHeaders anonymousJoinHeaders = new HttpHeaders();
        anonymousJoinHeaders.setContentType(MediaType.APPLICATION_JSON);
        var joinResponse = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of("blackPlayer", "Mallory", "joinToken", "g".repeat(43)), anonymousJoinHeaders),
                Map.class);
        assertThat(joinResponse.getStatusCode().is4xxClientError()).isTrue();
        assertThat(joinResponse.getBody().get("code")).isEqualTo("INVALID_PLAYER_TOKEN");

        // Also an empty-token anonymous join must not crash.
        var emptyTokenJoin = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of("blackPlayer", "Mallory", "joinToken", ""), anonymousJoinHeaders),
                Map.class);
        assertThat(emptyTokenJoin.getStatusCode().is4xxClientError()).isTrue();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
