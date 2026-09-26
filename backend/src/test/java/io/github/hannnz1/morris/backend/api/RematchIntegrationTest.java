package io.github.hannnz1.morris.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.service.GameSessionService;
import io.github.hannnz1.morris.backend.service.PlayerService;
import io.github.hannnz1.morris.backend.service.TokenService;
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
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Same RANDOM_PORT override as DrawOfferIntegrationTest/ResignAndCancelIntegrationTest -
// PostgresIntegrationTest's @SpringBootTest defaults to WebEnvironment.MOCK.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RematchIntegrationTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlayerService playerService;
    @Autowired private TokenService tokenService;
    @Autowired private GameSessionService gameSessions;
    @Autowired private GameSessionRepository games;
    @Autowired private PlayerRepository players;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void acceptingARematchOfferCreatesANewGameWithColorsSwapped() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        rematch(game.id(), white.token(), "OFFER");
        var accepted = rematch(game.id(), black.token(), "ACCEPT");

        UUID newGameId = UUID.fromString((String) accepted.getBody().get("rematchGameId"));
        assertThat(newGameId).isNotEqualTo(game.id());
        var newGame = rest.getForEntity(url("/api/v1/games/" + newGameId), Map.class).getBody();
        assertThat(newGame.get("whitePlayer")).isEqualTo(black.nickname()); // colors swapped
        assertThat(newGame.get("blackPlayer")).isEqualTo(white.nickname());
        assertThat(newGame.get("status")).isEqualTo("IN_PROGRESS"); // both seated immediately, no waiting
    }

    @Test
    void rematchExpiresFiveMinutesAfterTheGameFinished() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        ((MutableClock) clock).advance(Duration.ofMinutes(5).plusSeconds(1));
        var response = rematch(game.id(), white.token(), "OFFER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
    }

    @Test
    void bothPlayersOfferingIsTreatedAsAnImmediateAccept() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        rematch(game.id(), white.token(), "OFFER");
        var blackOffer = rematch(game.id(), black.token(), "OFFER");

        assertThat(blackOffer.getBody()).containsKey("rematchGameId");
        assertThat(blackOffer.getBody().get("rematchGameId")).isNotNull();
    }

    @Test
    void callingRematchAgainAfterTheNewGameExistsReturnsTheSameGameIdIdempotently() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        rematch(game.id(), white.token(), "OFFER");
        var first = rematch(game.id(), black.token(), "ACCEPT");
        var second = rematch(game.id(), white.token(), "ACCEPT"); // already-accepted, replays

        assertThat(second.getBody().get("rematchGameId")).isEqualTo(first.getBody().get("rematchGameId"));
    }

    @Test
    void anAbortedGameIsNotRematchable() {
        var white = createPlayer("Han");
        var created = createGame(white, "5+3");
        // No second player joins, so cancel is the way to reach a non-rematchable terminal
        // status here (ABORTED requires clock-timeout on a first move, which this test does not
        // drive) - CANCELLED already covers the "not WHITE_WON/BLACK_WON/DRAWN" branch directly.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(white.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var cancelResponse = rest.exchange(url("/api/v1/games/" + created.gameId() + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELLED");

        var response = rematch(created.gameId(), white.token(), "OFFER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("GAME_NOT_ACTIVE");
    }

    @Test
    void acceptingIsBlockedWhenAPlayerHasTooManyActiveGamesAndTheOriginalOfferIsUnchanged() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        rematch(game.id(), white.token(), "OFFER");

        // Seed 5 active (WAITING_FOR_PLAYER) games for black, straight through
        // GameSessionService.createForPlayer (no HTTP, no per-IP/per-player rate limit concerns -
        // same pattern as GameSessionServiceActiveGameLimitTest), so black is already at the cap
        // when the ACCEPT below tries to seat them in a 6th (the rematch) game.
        PlayerEntity blackEntity = playerEntityFor(black);
        PlayerEntity whiteEntity = playerEntityFor(white);
        for (int i = 0; i < 5; i++) {
            gameSessions.createForPlayer(blackEntity, "rematch-cap-seed-" + i, null);
        }

        var response = rematch(game.id(), black.token(), "ACCEPT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("TOO_MANY_ACTIVE_GAMES");

        // The original game's pending OFFER is untouched - spec M2.6's "旧对局的 OFFER 保持不变" -
        // and no rematch game was ever linked, since the whole method rolled back.
        GameSessionEntity original = games.findById(game.id()).orElseThrow();
        assertThat(original.getRematchOfferedBy()).isEqualTo("WHITE");
        assertThat(original.getRematchGameId()).isNull();

        // No extra IN_PROGRESS game with the colors swapped (white<->black) was created for the
        // two players - the cap check ran, and threw, before any such row could be written.
        boolean swappedGameExists = games.findAll().stream()
                .anyMatch(g -> "IN_PROGRESS".equals(g.getStatus())
                        && blackEntity.getId().equals(g.getWhitePlayerId())
                        && whiteEntity.getId().equals(g.getBlackPlayerId()));
        assertThat(swappedGameExists).isFalse();
    }

    @Test
    void aRematchOfAPreClockGameFallsBackToTheDefaultTimeControl() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        // Simulate a finished game from before the clock existed: base_ms/increment_ms are NULL.
        jdbc.update("UPDATE game_sessions SET base_ms = NULL, increment_ms = NULL WHERE id = ?", game.id());

        rematch(game.id(), white.token(), "OFFER");
        var accepted = rematch(game.id(), black.token(), "ACCEPT");

        UUID newGameId = UUID.fromString((String) accepted.getBody().get("rematchGameId"));
        var newGame = rest.getForEntity(url("/api/v1/games/" + newGameId), Map.class).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> clockView = (Map<String, Object>) newGame.get("clock");
        assertThat(((Number) clockView.get("whiteMs")).longValue()).isEqualTo(5 * 60_000L);
        assertThat(((Number) clockView.get("blackMs")).longValue()).isEqualTo(5 * 60_000L);
    }

    // A player who left a finished game must be able to find it again in 我的对局 while a rematch
    // is still possible, and see that the opponent is waiting on an answer.
    @Test
    void finishedGamesListShowsWhetherARematchIsStillOpenAndWhoOfferedIt() {
        var white = createPlayer("Han");
        var black = createPlayer("Zhu");
        var game = createAndJoinAndFinishByResignation(white, black);

        Map<String, Object> beforeOffer = finishedSummary(black, game.id());
        assertThat(beforeOffer.get("rematchOpen")).isEqualTo(true);
        assertThat(beforeOffer.get("opponentOfferedRematch")).isEqualTo(false);

        rematch(game.id(), white.token(), "OFFER");
        assertThat(finishedSummary(black, game.id()).get("opponentOfferedRematch")).isEqualTo(true);
        assertThat(finishedSummary(white, game.id()).get("opponentOfferedRematch")).isEqualTo(false);

        ((MutableClock) clock).advance(Duration.ofMinutes(5).plusSeconds(1));
        Map<String, Object> expired = finishedSummary(black, game.id());
        assertThat(expired.get("rematchOpen")).isEqualTo(false);
        assertThat(expired.get("opponentOfferedRematch")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> finishedSummary(TestPlayer player, UUID gameId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(player.token());
        var response = rest.exchange(url("/api/v1/players/me/games?status=FINISHED&limit=50"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((java.util.List<Map<String, Object>>) response.getBody().get("games")).stream()
                .filter(summary -> gameId.toString().equals(summary.get("gameId")))
                .findFirst().orElseThrow();
    }

    private PlayerEntity playerEntityFor(TestPlayer player) {
        return players.findByTokenHash(tokenService.hash(player.token())).orElseThrow();
    }

    private ResponseEntity<Map> rematch(UUID gameId, String token, String action) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return rest.exchange(url("/api/v1/games/" + gameId + "/rematch"), HttpMethod.POST,
                new HttpEntity<>(Map.of("action", action), headers), Map.class);
    }

    private record TestPlayer(String nickname, String token) {
    }

    private record CreatedGame(UUID gameId) {
    }

    private record JoinedGame(UUID id) {
    }

    // Goes straight through PlayerService.createOrGet rather than POST /api/v1/players - the HTTP
    // endpoint's per-IP rate limit is shared across every HTTP-level test class in this suite (same
    // reasoning as DrawOfferIntegrationTest.createPlayer).
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

    private JoinedGame createAndJoinAndFinishByResignation(TestPlayer white, TestPlayer black) {
        JoinedGame game = createAndJoinGame(white, black);

        HttpHeaders resignHeaders = new HttpHeaders();
        resignHeaders.setBearerAuth(white.token());
        resignHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        var resignResponse = rest.exchange(url("/api/v1/games/" + game.id() + "/resign"),
                HttpMethod.POST, new HttpEntity<>(resignHeaders), Map.class);
        assertThat(resignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resignResponse.getBody().get("status")).isEqualTo("BLACK_WON");
        return game;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
