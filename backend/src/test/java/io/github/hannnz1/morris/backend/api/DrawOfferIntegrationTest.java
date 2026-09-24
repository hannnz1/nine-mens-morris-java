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

// Same RANDOM_PORT override as ResignAndCancelIntegrationTest - PostgresIntegrationTest's
// @SpringBootTest defaults to WebEnvironment.MOCK, which doesn't expose TestRestTemplate/@LocalServerPort.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DrawOfferIntegrationTest extends PostgresIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlayerService playerService;
    @Autowired private TokenService tokenService;

    @Test
    void offerThenAcceptEndsTheGameAsADraw() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        draw(game.id(), white.token(), "OFFER");
        var accepted = draw(game.id(), black.token(), "ACCEPT");

        assertThat(accepted.getBody().get("status")).isEqualTo("DRAWN");
    }

    @Test
    void acceptingWithNoPendingOfferIsRejected() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        var response = draw(game.id(), black.token(), "ACCEPT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("NO_PENDING_OFFER");
    }

    @Test
    void aFourthDrawOfferInTheSameGameIsRejected() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        for (int i = 0; i < 3; i++) {
            draw(game.id(), white.token(), "OFFER");
            draw(game.id(), black.token(), "DECLINE");
        }
        var fourth = draw(game.id(), white.token(), "OFFER");

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fourth.getBody().get("code")).isEqualTo("OFFER_LIMIT_REACHED");
    }

    @Test
    void offeringAgainWithAnExistingOfferIsIdempotentNotDoubleCounted() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        var first = draw(game.id(), white.token(), "OFFER");
        var second = draw(game.id(), white.token(), "OFFER");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Confirm the count didn't move by exhausting the real limit next: two more offers (total
        // 3, not 4) must still succeed, and a genuine 4th must still be the one that's rejected.
        draw(game.id(), black.token(), "DECLINE");
        draw(game.id(), white.token(), "OFFER");
        draw(game.id(), black.token(), "DECLINE");
        var thirdRealOffer = draw(game.id(), white.token(), "OFFER");
        assertThat(thirdRealOffer.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bothPlayersOfferingAtOnceIsTreatedAsAnAcceptByTheSecondRequest() {
        // Simulates the row-lock serialization spec M2.6 describes: two sequential OFFER calls,
        // the second one made while a first OFFER from the OTHER side is already pending, must
        // immediately end the game as DRAW_AGREED rather than overwrite the first offer.
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        draw(game.id(), white.token(), "OFFER");
        var blackOfferWhileWhitesIsPending = draw(game.id(), black.token(), "OFFER");

        assertThat(blackOfferWhileWhitesIsPending.getBody().get("status")).isEqualTo("DRAWN");
    }

    @Test
    void aMoveByEitherPlayerClearsTheOtherPlayersPendingOffer() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        // White makes the opening placement first (it is White's turn), handing the turn to Black.
        performMove(game.id(), white.token(), "A1");
        draw(game.id(), white.token(), "OFFER");
        performMove(game.id(), black.token(), "D2"); // black declines by playing on

        var acceptAfterMove = draw(game.id(), black.token(), "ACCEPT");
        assertThat(acceptAfterMove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(acceptAfterMove.getBody().get("code")).isEqualTo("NO_PENDING_OFFER");
    }

    @Test
    void theOfferersOwnLaterMoveClearsTheirOwnPendingOffer() {
        // The other direction of "a move clears a pending offer" from
        // aMoveByEitherPlayerClearsTheOtherPlayersPendingOffer above: there the OPPONENT's move
        // clears the offer; here the OFFERER's own subsequent move must clear it too, per the
        // plan's Review Focus. OFFER isn't turn-gated, so White can offer on White's own turn and
        // then simply play their own move.
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        var offered = draw(game.id(), white.token(), "OFFER");
        assertThat(offered.getBody().get("drawOfferedBy")).isEqualTo("WHITE");

        performMove(game.id(), white.token(), "A1"); // White's own move, not Black's

        var afterMove = rest.exchange(url("/api/v1/games/" + game.id()), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(afterMove.getBody().get("drawOfferedBy")).isNull();

        var acceptAfterMove = draw(game.id(), black.token(), "ACCEPT");
        assertThat(acceptAfterMove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(acceptAfterMove.getBody().get("code")).isEqualTo("NO_PENDING_OFFER");
    }

    @Test
    void offerAddsDrawOfferedByToTheResponse() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        var offered = draw(game.id(), white.token(), "OFFER");

        assertThat(offered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(offered.getBody().get("drawOfferedBy")).isEqualTo("WHITE");
    }

    @Test
    void acceptPopulatesResultWithDrawAgreedReason() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinGame(white, black);

        draw(game.id(), white.token(), "OFFER");
        var accepted = draw(game.id(), black.token(), "ACCEPT");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) accepted.getBody().get("result");
        assertThat(result.get("winner")).isNull();
        assertThat(result.get("reason")).isEqualTo("DRAW_AGREED");
    }

    private ResponseEntity<Map> draw(UUID gameId, String token, String action) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return rest.exchange(url("/api/v1/games/" + gameId + "/draw"), HttpMethod.POST,
                new HttpEntity<>(Map.of("action", action), headers), Map.class);
    }

    // A single legal opening placement, reading the game's current version fresh (not hard-coded)
    // before each move.
    private void performMove(UUID gameId, String token, String toPosition) {
        var current = rest.exchange(url("/api/v1/games/" + gameId), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        long expectedVersion = ((Number) current.getBody().get("version")).longValue();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = Map.of(
                "type", "PLACE",
                "to", toPosition,
                "expectedVersion", expectedVersion);
        var response = rest.exchange(url("/api/v1/games/" + gameId + "/actions"), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private record TestPlayer(String nickname, String token) {
    }

    private record CreatedGame(UUID gameId) {
    }

    private record JoinedGame(UUID id) {
    }

    // Goes straight through PlayerService.createOrGet rather than POST /api/v1/players (see
    // ResignAndCancelIntegrationTest.createPlayer): that HTTP endpoint is rate-limited to 10
    // creations/minute per remote address, and HTTP-level test classes share one cached Spring
    // context/RateLimiter bean.
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
