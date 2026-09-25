package io.github.hannnz1.morris.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.service.PlayerService;
import io.github.hannnz1.morris.backend.service.TokenService;
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
// here (same pattern as RoomCodeIntegrationTest/PlayerApiIntegrationTest) switches just this test
// class to RANDOM_PORT so real HTTP round trips through Authorization headers work as written.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResignAndCancelIntegrationTest extends PostgresIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlayerService playerService;
    @Autowired private TokenService tokenService;

    @Test
    void resigningEndsTheGameInFavorOfTheOpponent() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(white.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var response = rest.exchange(url("/api/v1/games/" + game.id() + "/resign"),
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("BLACK_WON");
    }

    @Test
    void cancellingAWaitingGameYouCreatedMarksItCancelled() {
        var white = createPlayer("Han");
        var created = createGame(white, "5+3");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(white.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var response = rest.exchange(url("/api/v1/games/" + created.gameId() + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getBody().get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingAGameThatAlreadyHasBothPlayersIsRejected() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(white.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var response = rest.exchange(url("/api/v1/games/" + game.id() + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
    }

    @Test
    void resigningAnAlreadyFinishedGameIsRejectedAndDoesNotChangeTheStoredResult() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        HttpHeaders firstHeaders = new HttpHeaders();
        firstHeaders.setBearerAuth(white.token());
        firstHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        var firstResign = rest.exchange(url("/api/v1/games/" + game.id() + "/resign"),
                HttpMethod.POST, new HttpEntity<>(firstHeaders), Map.class);
        assertThat(firstResign.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResign.getBody().get("status")).isEqualTo("BLACK_WON");

        // Black now tries to resign the already-finished game, using a fresh key (not a replay of
        // white's resign) - must be rejected, not re-finish the game a second time.
        HttpHeaders secondHeaders = new HttpHeaders();
        secondHeaders.setBearerAuth(black.token());
        secondHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        var secondResign = rest.exchange(url("/api/v1/games/" + game.id() + "/resign"),
                HttpMethod.POST, new HttpEntity<>(secondHeaders), Map.class);
        assertThat(secondResign.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResign.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");

        var getResponse = rest.exchange(url("/api/v1/games/" + game.id()), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().get("status")).isEqualTo("BLACK_WON");
    }

    // Final-review C2: joinByBearer used to check only the black seat, so a CANCELLED game (which
    // has no black player) could be joined and brought back to life as IN_PROGRESS.
    @Test
    void joiningACancelledGameIsRejectedAndTheGameStaysCancelled() {
        var white = createPlayer("Han");
        var created = createGame(white, "5+3");
        HttpHeaders cancelHeaders = new HttpHeaders();
        cancelHeaders.setBearerAuth(white.token());
        cancelHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        var cancelled = rest.exchange(url("/api/v1/games/" + created.gameId() + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(cancelHeaders), Map.class);
        assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");

        var intruder = createPlayer("Zhu");
        HttpHeaders joinHeaders = new HttpHeaders();
        joinHeaders.setBearerAuth(intruder.token());
        joinHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        joinHeaders.setContentType(MediaType.APPLICATION_JSON);
        var join = rest.exchange(url("/api/v1/games/" + created.gameId() + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), joinHeaders), Map.class);

        assertThat(join.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(join.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
        var after = rest.getForEntity(url("/api/v1/games/" + created.gameId()), Map.class).getBody();
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("blackPlayer")).isNull();
        assertThat(after.get("blackPlayerId")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> clock = (Map<String, Object>) after.get("clock");
        assertThat(clock.get("running")).isEqualTo(false);
        assertThat(clock.get("turnDeadlineAt")).isNull();
    }

    // Final-review M-h: a move on a CANCELLED game used to hit the "black seat empty" check first
    // and report WAITING_FOR_PLAYER. It must be GAME_NOT_ACTIVE; a genuinely waiting game still
    // reports WAITING_FOR_PLAYER.
    @Test
    void aMoveOnACancelledGameIsGameNotActiveButOnAWaitingGameIsWaitingForPlayer() {
        var white = createPlayer("Han");
        var waiting = createGame(white, "5+3");
        var onWaiting = placeA1(waiting.gameId(), white.token());
        assertThat(onWaiting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onWaiting.getBody().get("code")).isEqualTo("WAITING_FOR_PLAYER");

        HttpHeaders cancelHeaders = new HttpHeaders();
        cancelHeaders.setBearerAuth(white.token());
        cancelHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        rest.exchange(url("/api/v1/games/" + waiting.gameId() + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(cancelHeaders), Map.class);

        var onCancelled = placeA1(waiting.gameId(), white.token());
        assertThat(onCancelled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onCancelled.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
    }

    @Test
    void gameResponsesCarryBothPlayerIds() {
        var white = createPlayer("Han");
        var black = createPlayer("Han"); // same nickname on purpose: ids, not names, tell sides apart
        var game = createAndJoinGame(white, black);

        var body = rest.getForEntity(url("/api/v1/games/" + game.id()), Map.class).getBody();

        assertThat(body.get("whitePlayerId")).isEqualTo(playerService.getByToken(white.token()).playerId().toString());
        assertThat(body.get("blackPlayerId")).isEqualTo(playerService.getByToken(black.token()).playerId().toString());
        assertThat(body.get("whitePlayerId")).isNotEqualTo(body.get("blackPlayerId"));
    }

    private ResponseEntity<Map> placeA1(UUID gameId, String token) {
        long version = ((Number) rest.getForEntity(url("/api/v1/games/" + gameId), Map.class).getBody().get("version")).longValue();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/v1/games/" + gameId + "/actions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "PLACE", "to", "A1", "expectedVersion", version), headers), Map.class);
    }

    private record TestPlayer(String nickname, String token) {
    }

    private record CreatedGame(UUID gameId) {
    }

    private record JoinedGame(UUID id) {
    }

    // Goes straight through PlayerService.createOrGet rather than POST /api/v1/players: that HTTP
    // endpoint is rate-limited to 10 creations/minute per remote address (PlayerController.create),
    // and every HTTP-level test in this suite shares 127.0.0.1 plus (often) the same cached Spring
    // context/RateLimiter bean. This test class alone registers several players per run, which was
    // enough to exhaust that shared per-IP budget and made RoomCodeIntegrationTest's own player
    // registrations - and everything downstream of them - fail with 429/401 when run in the same
    // window. The resign/cancel calls under test still go over real HTTP; only player bootstrapping
    // is shortcut, the same pattern PlayerServiceTest already uses.
    private TestPlayer createPlayer(String nickname) {
        String clientToken = tokenService.generate();
        playerService.createOrGet(new CreatePlayerRequest(nickname, clientToken));
        return new TestPlayer(nickname, clientToken);
    }

    private CreatedGame createGame(TestPlayer white, String timeControl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(white.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of("timeControl", timeControl), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object gameId = ((Map<?, ?>) response.getBody().get("game")).get("id");
        return new CreatedGame(UUID.fromString(gameId.toString()));
    }

    private JoinedGame createAndJoinGame(TestPlayer white, TestPlayer black) {
        CreatedGame created = createGame(white, "5+3");

        HttpHeaders joinHeaders = new HttpHeaders();
        joinHeaders.setBearerAuth(black.token());
        joinHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        joinHeaders.setContentType(MediaType.APPLICATION_JSON);
        var joinResponse = rest.exchange(url("/api/v1/games/" + created.gameId() + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), joinHeaders), Map.class);
        assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new JoinedGame(created.gameId());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
