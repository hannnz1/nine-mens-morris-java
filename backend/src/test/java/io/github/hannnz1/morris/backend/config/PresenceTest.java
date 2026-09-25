package io.github.hannnz1.morris.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.SeatResolver;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.engine.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PresenceTest {

    private GameSessionRepository games;
    private SeatResolver seatResolver;
    private ObjectMapper mapper;
    private MutableClock clock;
    private UUID gameId;

    private GameWebSocketHandler newHandler() {
        games = mock(GameSessionRepository.class);
        seatResolver = mock(SeatResolver.class);
        mapper = new ObjectMapper();
        clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        gameId = UUID.randomUUID();
        var entity = Mockito.mock(GameSessionEntity.class);
        when(games.findById(gameId)).thenReturn(Optional.of(entity));
        return new GameWebSocketHandler(games, seatResolver, mapper, clock);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private void subscribeAs(GameWebSocketHandler handler, WebSocketSession session, Player side) throws Exception {
        when(seatResolver.resolve(any(), any(), any())).thenReturn(side);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + "x".repeat(43) + "\"}"));
    }

    private List<String> payloadsSentTo(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }

    @Test
    void whiteSubscribingBroadcastsPresenceWithWhiteOnlineAndBlackOffline() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        List<String> payloads = payloadsSentTo(white);
        boolean sawCorrectPresence = payloads.stream().anyMatch(payload ->
                payload.contains("\"type\":\"PRESENCE\"")
                        && payload.contains("\"white\":\"ONLINE\"")
                        && payload.contains("\"black\":\"OFFLINE\""));
        assertThat(sawCorrectPresence).isTrue();

        // Never having connected, black must not be reported ONLINE.
        boolean everSawBlackOnline = payloads.stream().anyMatch(payload -> payload.contains("\"black\":\"ONLINE\""));
        assertThat(everSawBlackOnline).isFalse();
    }

    @Test
    void disconnectDoesNotImmediatelyReportOfflineButSweepDoesAfterFiveSeconds() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        handler.afterConnectionClosed(white, CloseStatus.NORMAL);
        assertThat(handler.isOnline(gameId, Player.WHITE)).isTrue(); // still "online" at t=0

        clock.advance(Duration.ofSeconds(4));
        handler.sweepPresence();
        assertThat(handler.isOnline(gameId, Player.WHITE)).isTrue(); // still within grace

        clock.advance(Duration.ofSeconds(2)); // total 6s since disconnect
        handler.sweepPresence();
        assertThat(handler.isOnline(gameId, Player.WHITE)).isFalse();
    }

    @Test
    void sweepBroadcastsExactlyOnceWhenSideGoesOffline() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);
        clearInvocations(white);

        handler.afterConnectionClosed(white, CloseStatus.NORMAL);

        clock.advance(Duration.ofSeconds(4));
        handler.sweepPresence();
        // No connection is subscribed anymore for "white" side to receive a broadcast, but check
        // via a fresh late subscriber that presence has not yet flipped.
        WebSocketSession lateJoiner = openSession("late1");
        subscribeAs(handler, lateJoiner, Player.BLACK);
        List<String> earlyPayloads = payloadsSentTo(lateJoiner);
        assertThat(earlyPayloads.stream().anyMatch(payload -> payload.contains("\"white\":\"ONLINE\""))).isTrue();
        clearInvocations(lateJoiner);

        clock.advance(Duration.ofSeconds(2)); // total 6s since white's disconnect
        handler.sweepPresence();

        List<String> latePayloads = payloadsSentTo(lateJoiner);
        long offlineBroadcasts = latePayloads.stream()
                .filter(payload -> payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"OFFLINE\""))
                .count();
        assertThat(offlineBroadcasts).isEqualTo(1);

        // A second sweep past the same already-reported transition must not re-broadcast: the
        // latch already matches the derived state, so recomputeAndBroadcastLocked finds no change.
        handler.sweepPresence();
        List<String> afterSecondSweep = payloadsSentTo(lateJoiner);
        long offlineBroadcastsAfterSecondSweep = afterSecondSweep.stream()
                .filter(payload -> payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"OFFLINE\""))
                .count();
        assertThat(offlineBroadcastsAfterSecondSweep).isEqualTo(1);
    }

    @Test
    void reconnectWithinFiveSecondsProducesNoPresenceBroadcast() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        WebSocketSession black = openSession("b1");
        subscribeAs(handler, black, Player.BLACK);
        clearInvocations(white, black);

        handler.afterConnectionClosed(white, CloseStatus.NORMAL);
        clock.advance(Duration.ofSeconds(3));
        handler.sweepPresence();

        // Reconnect within the 5s grace window.
        WebSocketSession whiteAgain = openSession("s2");
        subscribeAs(handler, whiteAgain, Player.WHITE);

        // The only message the reconnecting/observing connections should have seen for this game
        // is the SUBSCRIBED ack (plus the direct presence snapshot to the new connection which,
        // since presence never changed, must report white ONLINE, not a change notification to
        // the still-connected black side).
        verify(black, never()).sendMessage(any());

        List<String> whiteAgainPayloads = payloadsSentTo(whiteAgain);
        assertThat(whiteAgainPayloads.stream().anyMatch(payload ->
                payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"ONLINE\"")
                        && payload.contains("\"black\":\"ONLINE\""))).isTrue();
    }

    @Test
    void lateSubscriberReceivesCurrentPresenceDirectly() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        WebSocketSession black = openSession("b1");
        subscribeAs(handler, black, Player.BLACK);

        List<String> blackPayloads = payloadsSentTo(black);
        assertThat(blackPayloads.stream().anyMatch(payload ->
                payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"ONLINE\"")
                        && payload.contains("\"black\":\"ONLINE\""))).isTrue();
    }

    @Test
    void bothSidesOfflineWithNoConnectionsAreRemovedFromPresenceMap() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        handler.afterConnectionClosed(white, CloseStatus.NORMAL);
        clock.advance(Duration.ofSeconds(6));
        handler.sweepPresence();

        assertThat(handler.isOnline(gameId, Player.WHITE)).isFalse();
        assertThat(handler.isOnline(gameId, Player.BLACK)).isFalse();
        assertThat(handler.isTracked(gameId)).isFalse();
    }

    @Test
    void boundaryStillOnlineAtFourPointNineNineNineSecondsOfflineAtFiveSeconds() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);
        clearInvocations(white);

        handler.afterConnectionClosed(white, CloseStatus.NORMAL);

        clock.advance(Duration.ofMillis(4999));
        handler.sweepPresence();
        assertThat(handler.isOnline(gameId, Player.WHITE)).isTrue();

        // A late subscriber directly observes the still-ONLINE snapshot at 4.999s.
        WebSocketSession probe1 = openSession("probe1");
        subscribeAs(handler, probe1, Player.BLACK);
        assertThat(payloadsSentTo(probe1).stream().anyMatch(payload ->
                payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"ONLINE\""))).isTrue();

        clock.advance(Duration.ofMillis(1)); // now exactly 5.000s since disconnect
        handler.sweepPresence();
        assertThat(handler.isOnline(gameId, Player.WHITE)).isFalse();

        List<String> probe1Payloads = payloadsSentTo(probe1);
        assertThat(probe1Payloads.stream().anyMatch(payload ->
                payload.contains("\"type\":\"PRESENCE\"") && payload.contains("\"white\":\"OFFLINE\""))).isTrue();
    }

    @Test
    void pingJobSendsPingMessageToOpenSubscribedConnections() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);
        clearInvocations(white);

        handler.sendPings();

        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> captor =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(white, atLeastOnce()).sendMessage(captor.capture());
        boolean sawPing = captor.getAllValues().stream()
                .anyMatch(m -> m instanceof org.springframework.web.socket.PingMessage);
        assertThat(sawPing).isTrue();
    }

    @Test
    void pingJobSkipsAConnectionThatHasNotSubscribed() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession notSubscribed = openSession("unsubscribed1");
        handler.afterConnectionEstablished(notSubscribed);
        clearInvocations(notSubscribed);

        handler.sendPings();

        verify(notSubscribed, never()).sendMessage(any());
    }

    @Test
    void pingJobClosesOnlyTheFailingConnectionAndDoesNotThrow() throws Exception {
        GameWebSocketHandler handler = newHandler();
        WebSocketSession white = openSession("s1");
        subscribeAs(handler, white, Player.WHITE);

        WebSocketSession black = openSession("b1");
        subscribeAs(handler, black, Player.BLACK);
        clearInvocations(white, black);
        doThrow(new java.io.IOException("boom")).when(white).sendMessage(any());

        handler.sendPings();

        verify(white).close(any());

        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> captor =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(black, atLeastOnce()).sendMessage(captor.capture());
        boolean blackGotAPing = captor.getAllValues().stream()
                .anyMatch(m -> m instanceof org.springframework.web.socket.PingMessage);
        assertThat(blackGotAPing).isTrue();
    }

    @Test
    void deadConnectionDuringSubscribeDoesNotStayOnlineForeverAndGameEntryIsCleanedUp() throws Exception {
        // Critical C1 regression: a connection whose session is already closed by the time
        // SUBSCRIBE is processed must not get stuck registered as ONLINE forever, and the game's
        // Presence entry must eventually be cleaned up once nobody is online.
        GameWebSocketHandler handler = newHandler();
        WebSocketSession dead = mock(WebSocketSession.class);
        when(dead.getId()).thenReturn("dead1");
        when(dead.isOpen()).thenReturn(false);
        when(seatResolver.resolve(any(), any(), any())).thenReturn(Player.WHITE);

        handler.afterConnectionEstablished(dead);
        handler.handleTextMessage(dead, new TextMessage(
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + "x".repeat(43) + "\"}"));

        clock.advance(Duration.ofSeconds(6));
        handler.sweepPresence();

        assertThat(handler.isOnline(gameId, Player.WHITE)).isFalse();
        assertThat(handler.isTracked(gameId)).isFalse();
    }

    @Test
    void concurrentSubscribeCloseAndSweepConverge() throws Exception {
        // Important I1/I2 regression: hammer registerPresence/unregisterPresence/sweepPresence
        // concurrently on one game's Presence from many real threads. This can't prove the
        // absence of every race, but it must never deadlock, corrupt state, or throw, and once
        // everything quiesces the derived state must match reality.
        GameWebSocketHandler handler = newHandler();
        int threadsPerSide = 4;
        int iterationsPerThread = 25;
        String whiteToken = "W".repeat(43);
        String blackToken = "B".repeat(43);
        // Stubbed ONCE, before any thread starts, so there is no concurrent mockito stubbing -
        // only concurrent invocation of an already-fixed stub, keyed off the token each thread uses.
        when(seatResolver.resolve(any(), any(), any())).thenAnswer(invocation -> {
            String token = invocation.getArgument(1);
            return token.startsWith("W") ? Player.WHITE : Player.BLACK;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threadsPerSide * 2 + 1);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger idCounter = new AtomicInteger();
        AtomicBoolean stopSweeping = new AtomicBoolean(false);
        List<Future<?>> churners = new ArrayList<>();

        for (int i = 0; i < threadsPerSide; i++) {
            churners.add(pool.submit(() -> churn(handler, whiteToken, idCounter, iterationsPerThread, startGate)));
            churners.add(pool.submit(() -> churn(handler, blackToken, idCounter, iterationsPerThread, startGate)));
        }
        Future<?> sweeper = pool.submit(() -> {
            await(startGate);
            while (!stopSweeping.get()) {
                handler.sweepPresence();
            }
        });

        startGate.countDown();
        for (Future<?> f : churners) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        stopSweeping.set(true);
        sweeper.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // Deterministic tail: one permanent, still-open connection per side, subscribed last.
        // Uses the same token-keyed thenAnswer stub as the churners (not subscribeAs's
        // when(...).thenReturn(...), which would re-invoke and clobber that stub mid-setup).
        WebSocketSession whiteFinal = openSession("white-final");
        handler.afterConnectionEstablished(whiteFinal);
        handler.handleTextMessage(whiteFinal, new TextMessage(
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + whiteToken + "\"}"));
        WebSocketSession blackFinal = openSession("black-final");
        handler.afterConnectionEstablished(blackFinal);
        handler.handleTextMessage(blackFinal, new TextMessage(
                "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + blackToken + "\"}"));

        clock.advance(Duration.ofSeconds(6));
        handler.sweepPresence();

        assertThat(handler.isOnline(gameId, Player.WHITE)).isTrue();
        assertThat(handler.isOnline(gameId, Player.BLACK)).isTrue();

        String lastWhitePresence = lastPresencePayload(whiteFinal);
        String lastBlackPresence = lastPresencePayload(blackFinal);
        assertThat(lastWhitePresence).contains("\"white\":\"ONLINE\"").contains("\"black\":\"ONLINE\"");
        assertThat(lastBlackPresence).contains("\"white\":\"ONLINE\"").contains("\"black\":\"ONLINE\"");
    }

    private void churn(GameWebSocketHandler handler, String token, AtomicInteger idCounter, int iterations,
            CountDownLatch startGate) {
        await(startGate);
        for (int i = 0; i < iterations; i++) {
            WebSocketSession session = openSession("churn-" + token.charAt(0) + "-" + idCounter.incrementAndGet());
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage(
                    "{\"type\":\"SUBSCRIBE\",\"gameId\":\"" + gameId + "\",\"token\":\"" + token + "\"}"));
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private String lastPresencePayload(WebSocketSession session) throws Exception {
        List<String> payloads = payloadsSentTo(session);
        return payloads.stream()
                .filter(payload -> payload.contains("\"type\":\"PRESENCE\""))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
