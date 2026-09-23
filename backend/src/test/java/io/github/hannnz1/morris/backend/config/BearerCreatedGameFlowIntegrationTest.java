package io.github.hannnz1.morris.backend.config;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// M1 final whole-branch review I3: SeatResolverTest and friends only ever built games with
// non-null legacy hashes (gameWithLegacySeats), which is exactly why the C1/I1 null-hash NPE bugs
// went unnoticed. This exercises a game created through the REAL createForPlayer path (not
// hand-constructed with assignPlayers) end-to-end via the Bearer credential: submitting an
// action, restoring/fetching the session, and subscribing over the WebSocket handler. Lives in
// this package (not .api) because it calls the handler's package-private-protected
// handleTextMessage directly, exactly like GameWebSocketHandlerTest does.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BearerCreatedGameFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @LocalServerPort
    private int port;
    @Autowired
    private GameWebSocketHandler webSocketHandler;

    @Test
    void aBearerCreatedGamePlaysThroughActionsRestoreAndWebSocketSubscribe() throws Exception {
        String whiteToken = "w".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Alice", "clientToken", whiteToken), Map.class);
        String blackToken = "b".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Bob", "clientToken", blackToken), Map.class);

        HttpHeaders createHeaders = bearer(whiteToken);
        createHeaders.set("Idempotency-Key", "e2e-create");
        var createResponse = rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), createHeaders), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object gameId = ((Map<?, ?>) createResponse.getBody().get("game")).get("id");

        HttpHeaders joinHeaders = bearer(blackToken);
        joinHeaders.set("Idempotency-Key", "e2e-join");
        var joinResponse = rest.exchange(url("/api/v1/games/" + gameId + "/join"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), joinHeaders), Map.class);
        assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Submit an action authenticated purely via Bearer (no X-Player-Token at all) - this is
        // the code path that used to NPE against a Bearer-created game's null legacy hashes.
        HttpHeaders actionHeaders = bearer(whiteToken);
        actionHeaders.set("Idempotency-Key", "e2e-action-1");
        var actionResponse = rest.exchange(url("/api/v1/games/" + gameId + "/actions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "PLACE", "to", "A1", "expectedVersion", 1), actionHeaders), Map.class);
        assertThat(actionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) actionResponse.getBody().get("version")).longValue()).isEqualTo(2);

        // Restore/fetch the session via Bearer only.
        HttpHeaders sessionHeaders = bearer(whiteToken);
        var sessionResponse = rest.exchange(url("/api/v1/games/" + gameId + "/session"), HttpMethod.GET,
                new HttpEntity<>(sessionHeaders), Map.class);
        assertThat(sessionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionResponse.getBody().get("id")).isEqualTo(gameId.toString());

        // WebSocket subscribe: the real, Spring-managed handler resolves the same raw client
        // token both as a bearer player token and as a legacy seat token (see
        // GameWebSocketHandler's SUBSCRIBE handling) against the actual persisted, Bearer-created
        // game row - not a hand-built entity.
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        webSocketHandler.afterConnectionEstablished(session);
        try {
            webSocketHandler.handleTextMessage(session,
                    new TextMessage("{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + whiteToken + "\"}"));
            verify(session).sendMessage(argThat(message -> message.getPayload().toString().contains("SUBSCRIBED")));
        } finally {
            webSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
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
