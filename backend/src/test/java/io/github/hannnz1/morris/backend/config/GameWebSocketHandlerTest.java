package io.github.hannnz1.morris.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.service.SeatResolver;
import io.github.hannnz1.morris.backend.service.TokenService;
import io.github.hannnz1.morris.engine.GameEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameWebSocketHandlerTest {
    private final GameSessionRepository games = mock(GameSessionRepository.class);
    private final TokenService tokens = new TokenService();
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final SeatResolver seatResolver = new SeatResolver(tokens, players);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final GameWebSocketHandler handler = new GameWebSocketHandler(games, seatResolver, mapper, java.time.Clock.systemUTC());
    private final UUID gameId = UUID.randomUUID();

    @AfterEach void close() { handler.shutdown(); }

    @Test
    void onlyAuthenticatedPlayersReceiveTheirGamesUpdates() throws Exception {
        allow(gameId);
        UUID otherId = UUID.randomUUID();
        allow(otherId);
        var white = open(); var black = open(); var other = open(); var unverified = open();
        subscribe(white, gameId, "white-secret");
        subscribe(black, gameId, "black-secret");
        subscribe(other, otherId, "white-secret");
        verify(white).sendMessage(argThat(message -> message.getPayload().toString().contains("SUBSCRIBED")));
        clearInvocations(white, black, other, unverified);
        handler.broadcast(gameId, response(1));
        for (var session : List.of(white, black)) {
            verify(session).sendMessage(argThat(message -> message.getPayload().toString().contains("GAME_STATE")));
        }
        verify(other, never()).sendMessage(any());
        verify(unverified, never()).sendMessage(any());
    }

    @Test
    void rejectsMissingWrongAndOtherGamesCredentialsAndMalformedMessages() throws Exception {
        allow(gameId);
        UUID otherId = UUID.randomUUID();
        when(games.findById(otherId)).thenReturn(Optional.of(new GameSessionEntity(otherId, "Other", null,
                tokens.hash("other-secret"), "", "WAITING_FOR_PLAYER", "{}", Instant.now())));
        for (String json : List.of(
                "{}", "null", "not-json", "{\"type\":\"SEND\"}",
                subscription(gameId, ""), subscription(gameId, "wrong"), subscription(otherId, "white-secret"),
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"*\",\"token\":\"white-secret\"}",
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\"}")) {
            var session = open();
            handler.handleTextMessage(session, new TextMessage(json));
            verify(session).sendMessage(argThat(message -> message.getPayload().toString().contains("ERROR")
                    && !message.getPayload().toString().contains("secret")));
            verify(session).close(CloseStatus.POLICY_VIOLATION);
            clearInvocations(session);
            handler.broadcast(gameId, response(1));
            verify(session, never()).sendMessage(any());
        }
    }

    @Test
    void authenticatedConnectionsCannotPublishActionsOrRebind() throws Exception {
        allow(gameId);
        for (String json : List.of("{\"type\":\"GAME_STATE\",\"game\":{}}", "{\"type\":\"PLACE\"}",
                subscription(gameId, "white-secret"))) {
            var session = open();
            subscribe(session, gameId, "white-secret");
            clearInvocations(session);
            handler.handleTextMessage(session, new TextMessage(json));
            verify(session).sendMessage(argThat(message -> message.getPayload().toString().contains("MESSAGE_NOT_ALLOWED")));
            verify(session).close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Test
    void binaryMessagesAreRejected() throws Exception {
        var session = open();
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1}));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void closedAndBrokenConnectionsAreRemovedWithoutAffectingTheOtherPlayer() throws Exception {
        allow(gameId);
        var closed = open(); var broken = open(); var healthy = open();
        for (var session : List.of(closed, broken, healthy)) subscribe(session, gameId, "white-secret");
        handler.afterConnectionClosed(closed, CloseStatus.NORMAL);
        clearInvocations(closed, broken, healthy);
        doThrow(new IOException("simulated failed connection")).when(broken).sendMessage(any());
        handler.broadcast(gameId, response(1));
        verify(closed, never()).sendMessage(any());
        verify(broken).close(CloseStatus.SERVER_ERROR);
        verify(healthy).sendMessage(any());
        clearInvocations(broken, healthy);
        handler.broadcast(gameId, response(2));
        verify(broken, never()).sendMessage(any());
        verify(healthy).sendMessage(any());
    }

    @Test
    void concurrentBroadcastsNeverWriteToTheSameUnderlyingSocketConcurrently() throws Exception {
        allow(gameId);
        var session = open();
        subscribe(session, gameId, "white-secret");
        clearInvocations(session);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        doAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
            finally { active.decrementAndGet(); }
            return null;
        }).when(session).sendMessage(any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> handler.broadcast(gameId, response(1)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> second = workers.submit(() -> handler.broadcast(gameId, response(2)));
            second.get(2, TimeUnit.SECONDS); // Queued in the decorator, not a concurrent socket write.
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            assertThat(maximum.get()).isEqualTo(1);
            verify(session, times(2)).sendMessage(any());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test
    void unauthenticatedConnectionsExpire() throws Exception {
        var session = open();
        await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> verify(session).close(CloseStatus.POLICY_VIOLATION));
        verify(session).sendMessage(argThat(message -> message.getPayload().toString().contains("AUTH_TIMEOUT")));
    }

    private void allow(UUID id) {
        when(games.findById(id)).thenReturn(Optional.of(new GameSessionEntity(id, "Alice", "Bob",
                tokens.hash("white-secret"), tokens.hash("black-secret"), "IN_PROGRESS", "{}", Instant.now())));
    }

    private WebSocketSession open() {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private String subscription(UUID id, String token) throws Exception {
        return mapper.writeValueAsString(Map.of("type", "SUBSCRIBE", "gameId", id, "token", token));
    }

    private void subscribe(WebSocketSession session, UUID id, String token) throws Exception {
        handler.handleTextMessage(session, new TextMessage(subscription(id, token)));
        // Presence broadcasts/snapshots triggered by SUBSCRIBE are now enqueued to a dedicated
        // executor rather than sent inline (round 3 fix for a lock-order-inversion deadlock), so
        // tests that assert exact sendMessage() call counts must wait for that queue to drain
        // first - otherwise a presence send can land after clearInvocations() and inflate a count.
        handler.awaitPresenceSends();
    }

    private GameResponse response(long version) {
        var state = GameEngine.newGame().state();
        return new GameResponse(gameId, version, "Alice", "Bob", "IN_PROGRESS", state.phase(), state,
                List.of(), Map.of(), List.of(), Instant.now(), Instant.now(),
                new io.github.hannnz1.morris.backend.api.GameApiDtos.ClockView(0, 0, false, Instant.now(), null),
                null, null, null, null, null, null, null, null, null);
    }
}
