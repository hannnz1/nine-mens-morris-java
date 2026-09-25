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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        verify(black, atLeastOnce()).sendMessage(any());
    }
}
