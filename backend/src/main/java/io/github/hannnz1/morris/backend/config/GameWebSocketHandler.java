package io.github.hannnz1.morris.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.SeatResolver;
import io.github.hannnz1.morris.engine.Player;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A connection receives snapshots for exactly one authenticated game. Operations use REST. */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final Duration PRESENCE_GRACE = Duration.ofSeconds(5);
    private final GameSessionRepository games;
    private final SeatResolver seatResolver;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Presence> presenceByGame = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "game-websocket-auth-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public GameWebSocketHandler(GameSessionRepository games, SeatResolver seatResolver, ObjectMapper mapper, Clock clock) {
        this.games = games;
        this.seatResolver = seatResolver;
        this.mapper = mapper;
        this.clock = clock;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(1024);
        Connection connection = new Connection(session);
        synchronized (connection) {
            connections.put(session.getId(), connection);
            connection.deadline = deadlines.schedule(() -> {
                synchronized (connection) {
                    if (connection.gameId == null && connections.get(session.getId()) == connection) {
                        reject(connection, "AUTH_TIMEOUT", CloseStatus.POLICY_VIOLATION);
                    }
                }
            }, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        synchronized (connection) {
            if (connections.get(session.getId()) != connection) return;
            if (connection.gameId != null) {
                reject(connection, "MESSAGE_NOT_ALLOWED", CloseStatus.POLICY_VIOLATION);
                return;
            }
            try {
                var body = mapper.readTree(message.getPayload());
                if (body == null || !body.isObject() || body.size() != 3
                        || !"SUBSCRIBE".equals(body.path("type").asText())
                        || !body.path("gameId").isTextual() || !body.path("token").isTextual()) {
                    reject(connection, "INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
                    return;
                }
                UUID gameId = UUID.fromString(body.get("gameId").asText());
                String token = body.get("token").asText();
                if (token.isBlank() || token.length() > 128) {
                    reject(connection, "INVALID_PLAYER_TOKEN", CloseStatus.POLICY_VIOLATION);
                    return;
                }
                var game = games.findById(gameId).orElse(null);
                if (game == null) {
                    reject(connection, "INVALID_PLAYER_TOKEN", CloseStatus.POLICY_VIOLATION);
                    return;
                }
                Player side;
                try {
                    // The same token is tried both as a Bearer player token and as a legacy seat
                    // token: both are independent 256-bit random values from TokenService.generate(),
                    // so there is no realistic collision between the two token spaces.
                    side = seatResolver.resolve(game, token, token);
                } catch (ApiException exception) {
                    reject(connection, exception.code(), CloseStatus.POLICY_VIOLATION);
                    return;
                }
                // Register BEFORE acknowledging. A snapshot read after this acknowledgement
                // covers earlier commits; later commits can also reach this connection.
                connection.gameId = gameId;
                connection.side = side;
                connection.deadline.cancel(false);
                send(connection, mapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "gameId", gameId)));

                Presence presence = presenceByGame.computeIfAbsent(gameId, id -> new Presence());
                Set<String> ids = side == Player.WHITE ? presence.whiteConnectionIds : presence.blackConnectionIds;
                ids.add(session.getId());
                if (side == Player.WHITE) presence.whiteDisconnectedAt = null;
                else presence.blackDisconnectedAt = null;
                // Recompute {white, black} and broadcast only if it changed from the last-broadcast
                // state (spec: PRESENCE is pushed on change, not on every subscribe).
                recomputeAndBroadcastIfChanged(gameId, presence);
                // A late joiner always learns the opponent's current state directly, even when the
                // recompute above found no change to broadcast.
                send(connection, buildPresencePayload(presence.lastWhiteOnline, presence.lastBlackOnline));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                reject(connection, "INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
            } catch (RuntimeException exception) {
                LOGGER.warn("WebSocket subscription failed for connection {}", session.getId(), exception);
                reject(connection, "INTERNAL_ERROR", CloseStatus.SERVER_ERROR);
            }
        }
    }

    public void broadcast(UUID gameId, GameResponse game) {
        final String payload;
        try {
            payload = mapper.writeValueAsString(Map.of("type", "GAME_STATE", "game", game));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize game notification", exception);
        }
        for (Connection connection : connections.values()) {
            if (gameId.equals(connection.gameId)) send(connection, payload);
        }
    }

    private void send(Connection connection, String payload) {
        try {
            if (!connection.session.isOpen()) {
                remove(connection);
                return;
            }
            // The decorator serializes concurrent writes and bounds queued data/slow sends.
            connection.session.sendMessage(new TextMessage(payload));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Game notification failed for connection {}", connection.session.getId(), exception);
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private void reject(Connection connection, String code, CloseStatus status) {
        remove(connection);
        try {
            send(connection, mapper.writeValueAsString(Map.of("type", "ERROR", "code", code)));
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Could not encode WebSocket error");
        } finally {
            close(connection, status);
        }
    }

    private void remove(Connection connection) {
        connections.remove(connection.session.getId(), connection);
        if (connection.deadline != null) connection.deadline.cancel(false);
        if (connection.gameId != null && connection.side != null) {
            Presence presence = presenceByGame.get(connection.gameId);
            if (presence != null) {
                Set<String> ids = connection.side == Player.WHITE
                        ? presence.whiteConnectionIds : presence.blackConnectionIds;
                ids.remove(connection.session.getId());
                // Do NOT broadcast here: OFFLINE is only ever reported by sweepPresence(), after the
                // full 5-second grace period has elapsed (spec M2.7's "掉线 5 秒后才推送离线").
                if (ids.isEmpty()) {
                    if (connection.side == Player.WHITE) presence.whiteDisconnectedAt = clock.instant();
                    else presence.blackDisconnectedAt = clock.instant();
                }
            }
        }
    }

    /** Every 25 seconds (spec M2.8), ping every open, subscribed connection to detect half-open sockets. */
    @Scheduled(fixedRate = 25_000)
    public void scheduledPing() {
        sendPings();
    }

    void sendPings() {
        for (Connection connection : connections.values()) {
            if (connection.gameId == null) continue;
            try {
                if (!connection.session.isOpen()) {
                    remove(connection);
                    continue;
                }
                connection.session.sendMessage(new PingMessage());
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Ping failed for connection {}; closing it", connection.session.getId(), exception);
                close(connection, CloseStatus.SERVER_ERROR);
            }
        }
    }

    /** Once per second (spec M2.7), pushes any presence changes caused purely by the passage of
     * time - i.e. a side crossing the 5-second offline grace period with no reconnection. */
    @Scheduled(fixedDelay = 1000)
    public void scheduledPresenceSweep() {
        sweepPresence();
    }

    void sweepPresence() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, Presence> entry : presenceByGame.entrySet()) {
            UUID gameId = entry.getKey();
            Presence presence = entry.getValue();
            recomputeAndBroadcastIfChanged(gameId, presence, now);
            if (!presence.lastWhiteOnline && !presence.lastBlackOnline
                    && presence.whiteConnectionIds.isEmpty() && presence.blackConnectionIds.isEmpty()) {
                presenceByGame.remove(gameId, presence);
            }
        }
    }

    /** True online/offline as of right now (not merely the last-broadcast state), so callers get a
     * live answer even between sweeps. A side that never connected has no Presence entry: offline. */
    boolean isOnline(UUID gameId, Player side) {
        Presence presence = presenceByGame.get(gameId);
        if (presence == null) return false;
        Instant now = clock.instant();
        return side == Player.WHITE
                ? computeOnline(presence.whiteConnectionIds, presence.whiteDisconnectedAt, now)
                : computeOnline(presence.blackConnectionIds, presence.blackDisconnectedAt, now);
    }

    private boolean computeOnline(Set<String> connectionIds, Instant disconnectedAt, Instant now) {
        if (!connectionIds.isEmpty()) return true;
        if (disconnectedAt == null) return false;
        return Duration.between(disconnectedAt, now).compareTo(PRESENCE_GRACE) < 0;
    }

    private void recomputeAndBroadcastIfChanged(UUID gameId, Presence presence) {
        recomputeAndBroadcastIfChanged(gameId, presence, clock.instant());
    }

    private void recomputeAndBroadcastIfChanged(UUID gameId, Presence presence, Instant now) {
        boolean whiteOnline = computeOnline(presence.whiteConnectionIds, presence.whiteDisconnectedAt, now);
        boolean blackOnline = computeOnline(presence.blackConnectionIds, presence.blackDisconnectedAt, now);
        boolean changed;
        synchronized (presence) {
            changed = presence.lastWhiteOnline != whiteOnline || presence.lastBlackOnline != blackOnline;
            presence.lastWhiteOnline = whiteOnline;
            presence.lastBlackOnline = blackOnline;
        }
        if (changed) broadcastPresence(gameId, whiteOnline, blackOnline);
    }

    private void broadcastPresence(UUID gameId, boolean whiteOnline, boolean blackOnline) {
        final String payload;
        try {
            payload = buildPresencePayload(whiteOnline, blackOnline);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize presence notification", exception);
        }
        for (Connection connection : connections.values()) {
            if (gameId.equals(connection.gameId)) send(connection, payload);
        }
    }

    private String buildPresencePayload(boolean whiteOnline, boolean blackOnline) throws JsonProcessingException {
        return mapper.writeValueAsString(Map.of(
                "type", "PRESENCE",
                "white", whiteOnline ? "ONLINE" : "OFFLINE",
                "black", blackOnline ? "ONLINE" : "OFFLINE"));
    }

    private void close(Connection connection, CloseStatus status) {
        remove(connection);
        try { connection.session.close(status); }
        catch (IOException | RuntimeException exception) {
            LOGGER.debug("Could not close connection {}", connection.session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.get(session.getId());
        if (connection != null) synchronized (connection) { remove(connection); }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null) synchronized (connection) { close(connection, CloseStatus.SERVER_ERROR); }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection != null) synchronized (connection) {
            reject(connection, "INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
        }
    }

    @PreDestroy
    public void shutdown() {
        deadlines.shutdownNow();
        connections.values().forEach(connection -> close(connection, CloseStatus.GOING_AWAY));
    }

    private static final class Connection {
        private final WebSocketSession session;
        private volatile UUID gameId;
        private volatile Player side;
        private ScheduledFuture<?> deadline;

        private Connection(WebSocketSession session) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, 64 * 1024);
        }
    }

    /** Tracks, per game, which connection ids are currently subscribed on each side and the
     * instant (if any) the last connection on that side dropped. {@code lastWhiteOnline}/
     * {@code lastBlackOnline} hold the state last broadcast to clients, so a recompute only
     * triggers a PRESENCE message when the derived {white, black} pair actually differs from it. */
    private static final class Presence {
        final Set<String> whiteConnectionIds = ConcurrentHashMap.newKeySet();
        final Set<String> blackConnectionIds = ConcurrentHashMap.newKeySet();
        volatile Instant whiteDisconnectedAt;
        volatile Instant blackDisconnectedAt;
        volatile boolean lastWhiteOnline;
        volatile boolean lastBlackOnline;
    }
}
