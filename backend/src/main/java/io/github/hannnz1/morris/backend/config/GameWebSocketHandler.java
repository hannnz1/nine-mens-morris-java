package io.github.hannnz1.morris.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.SeatResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A connection receives snapshots for exactly one authenticated game. Operations use REST. */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private final GameSessionRepository games;
    private final SeatResolver seatResolver;
    private final ObjectMapper mapper;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "game-websocket-auth-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public GameWebSocketHandler(GameSessionRepository games, SeatResolver seatResolver, ObjectMapper mapper) {
        this.games = games;
        this.seatResolver = seatResolver;
        this.mapper = mapper;
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
                try {
                    // The same token is tried both as a Bearer player token and as a legacy seat
                    // token: both are independent 256-bit random values from TokenService.generate(),
                    // so there is no realistic collision between the two token spaces.
                    seatResolver.resolve(game, token, token);
                } catch (ApiException exception) {
                    reject(connection, exception.code(), CloseStatus.POLICY_VIOLATION);
                    return;
                }
                // Register BEFORE acknowledging. A snapshot read after this acknowledgement
                // covers earlier commits; later commits can also reach this connection.
                connection.gameId = gameId;
                connection.deadline.cancel(false);
                send(connection, mapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "gameId", gameId)));
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
        private ScheduledFuture<?> deadline;

        private Connection(WebSocketSession session) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, 64 * 1024);
        }
    }
}
