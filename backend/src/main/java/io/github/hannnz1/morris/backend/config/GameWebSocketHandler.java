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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    /**
     * A dedicated, single-thread, FIFO executor for every send made while a game's presence lock
     * is held (the recompute-driven broadcast and the direct snapshot to a new subscriber).
     *
     * A blocking container send can, on some send failures, cause the servlet container to invoke
     * Spring's {@code afterConnectionClosed} SYNCHRONOUSLY on the sending thread (confirmed for
     * Tomcat: {@code WsRemoteEndpointImplBase} -> {@code WsSession.doClose} ->
     * {@code fireEndpointOnClose}), which then takes {@code synchronized (connection)}. If that send
     * happened while THIS thread already held a game's presence lock, and some other thread was
     * meanwhile holding that same connection's monitor while itself waiting on the presence lock
     * (e.g. mid-SUBSCRIBE), the two threads deadlock - lock-order inversion between the presence
     * lock and the connection monitor.
     *
     * The fix: the presence lock is only ever used to derive state, compare-and-swap the
     * last-broadcast latch, and build the payload/SUBMIT the send here - never to actually perform
     * the container send. Because submission happens while still holding the presence lock,
     * FIFO submission order preserves the per-game delivery order the latch depends on (I2). The
     * executor thread itself never holds a presence lock while sending, so if a send fails and the
     * container synchronously calls {@code afterConnectionClosed} on this executor thread, that
     * thread simply takes connection-then-presence in the normal order - no inversion possible.
     */
    private final ExecutorService presenceSender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "presence-sender");
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
                connection.deadline.cancel(false);

                // Presence registration - assigning connection.gameId/side, adding the id,
                // recompute/broadcast, and enqueuing the direct snapshot - happens entirely under
                // the game's presence lock, and BEFORE the SUBSCRIBED ack is sent. This closes the
                // gap where the ping thread could see a subscribed gameId before registration. If
                // this connection is already dead, the enqueued send fails on the presence-sender
                // thread and unregisters it there (that thread holds no lock at the time, so no
                // lock-order inversion), so a dead connection never gets stuck registered ONLINE.
                registerPresence(gameId, side, connection);

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
        UUID gameId = connection.gameId;
        Player side = connection.side;
        if (gameId != null && side != null) {
            unregisterPresence(gameId, side, connection.session.getId());
        }
    }

    /**
     * Registers a newly subscribed connection with the game's presence entry, using the standard
     * computeIfAbsent-then-recheck retry loop so a concurrent sweepPresence() that just removed this
     * game's Presence (because it saw both sides offline with no connections, Important I1) cannot
     * leave this registration silently attached to an orphaned, already-removed Presence instance:
     * if {@code removed} is set by the time we get the lock, we retry and computeIfAbsent installs a
     * fresh Presence.
     *
     * Every presence mutation for this game - assigning connection.gameId/side, registering the id,
     * clearing disconnectedAt, the recompute-and-latch-compare, and ENQUEUING both the broadcast and
     * this connection's direct snapshot to {@link #presenceSender} - happens while holding the SAME
     * {@code synchronized (presence)} lock (Important I2), so a concurrent recompute can never
     * interleave with this one, and (since enqueue order under the lock matches delivery order on
     * the single-thread executor) this game's PRESENCE messages stay in order. Nothing here ever
     * calls a container send directly, so this method itself can never deadlock against a Tomcat
     * synchronous close callback. The direct snapshot enqueue only happens after registration, and
     * the SUBSCRIBED ack (sent by the caller, outside this lock) only happens after this method
     * returns - both still close the dead-connection leak in Critical C1.
     */
    private void registerPresence(UUID gameId, Player side, Connection connection) {
        while (true) {
            Presence presence = presenceByGame.computeIfAbsent(gameId, id -> new Presence());
            synchronized (presence) {
                if (presence.removed) continue;
                // Assign the connection's game/side identity here, under the presence lock, so the
                // ping job can never observe a subscribed gameId before presence registration.
                connection.gameId = gameId;
                connection.side = side;
                Set<String> ids = side == Player.WHITE ? presence.whiteConnectionIds : presence.blackConnectionIds;
                ids.add(connection.session.getId());
                if (side == Player.WHITE) presence.whiteDisconnectedAt = null;
                else presence.blackDisconnectedAt = null;
                recomputeAndBroadcastLocked(gameId, presence, clock.instant());
                // A late joiner always learns the opponent's current state directly, even when the
                // recompute above found no change worth broadcasting to everyone else.
                String snapshot = buildPresencePayloadOrNull(gameId, presence.lastWhiteOnline, presence.lastBlackOnline);
                if (snapshot != null) enqueueSend(connection, snapshot);
                return;
            }
        }
    }

    /**
     * Unregisters one connection id from its side's presence set. If that was the side's last
     * connection, stamps a disconnect time but does NOT broadcast here - OFFLINE is only ever
     * reported by sweepPresence(), after the full 5-second grace period has elapsed (spec M2.7's
     * "掉线 5 秒后才推送离线"). Called via remove() from, among others, {@link #send} on the
     * {@link #presenceSender} thread when an enqueued presence send fails (Critical C1's
     * dead-connection case) or from the container's own callback thread when a send elsewhere
     * fails/closes. Neither of those callers holds a presence lock at that point (sends are never
     * performed while one is held - see {@link #presenceSender}'s javadoc), so this is always a
     * plain, uncontended acquisition of this Presence's monitor, never a nested/reentrant one.
     */
    private void unregisterPresence(UUID gameId, Player side, String connectionId) {
        Presence presence = presenceByGame.get(gameId);
        if (presence == null) return;
        synchronized (presence) {
            if (presence.removed) return;
            Set<String> ids = side == Player.WHITE ? presence.whiteConnectionIds : presence.blackConnectionIds;
            ids.remove(connectionId);
            if (ids.isEmpty()) {
                Instant now = clock.instant();
                if (side == Player.WHITE) presence.whiteDisconnectedAt = now;
                else presence.blackDisconnectedAt = now;
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
            synchronized (presence) {
                if (presence.removed) continue;
                recomputeAndBroadcastLocked(gameId, presence, now);
                if (!presence.lastWhiteOnline && !presence.lastBlackOnline
                        && presence.whiteConnectionIds.isEmpty() && presence.blackConnectionIds.isEmpty()) {
                    // Important I1: flip removed and unpublish under the SAME lock a racing
                    // registerPresence() checks, so that thread is guaranteed to see either the
                    // live entry (and register into it) or removed=true (and retry with a fresh one)
                    // - never a state where it silently registers into an orphaned Presence.
                    presence.removed = true;
                    presenceByGame.remove(gameId, presence);
                }
            }
        }
    }

    /** True online/offline as of right now (not merely the last-broadcast state), so callers get a
     * live answer even between sweeps. A side that never connected has no Presence entry: offline. */
    boolean isOnline(UUID gameId, Player side) {
        Presence presence = presenceByGame.get(gameId);
        if (presence == null) return false;
        synchronized (presence) {
            if (presence.removed) return false;
            Instant now = clock.instant();
            return side == Player.WHITE
                    ? computeOnline(presence.whiteConnectionIds, presence.whiteDisconnectedAt, now)
                    : computeOnline(presence.blackConnectionIds, presence.blackDisconnectedAt, now);
        }
    }

    /** Test accessor: whether a game still has a live Presence entry tracked at all. */
    boolean isTracked(UUID gameId) {
        return presenceByGame.containsKey(gameId);
    }

    private boolean computeOnline(Set<String> connectionIds, Instant disconnectedAt, Instant now) {
        if (!connectionIds.isEmpty()) return true;
        if (disconnectedAt == null) return false;
        return Duration.between(disconnectedAt, now).compareTo(PRESENCE_GRACE) < 0;
    }

    /** Recomputes {white, black} and, only if it differs from the last-broadcast latch, updates the
     * latch and ENQUEUES the broadcast (never sends directly - see {@link #presenceSender}) under
     * the SAME lock (Important I2: no stale recompute can ever overwrite a newer latch, and - since
     * the enqueue order under this lock matches FIFO delivery order on the single-thread executor -
     * no two broadcasts for this game can ever be delivered out of order). Caller MUST already hold
     * {@code synchronized (presence)}. */
    private void recomputeAndBroadcastLocked(UUID gameId, Presence presence, Instant now) {
        boolean whiteOnline = computeOnline(presence.whiteConnectionIds, presence.whiteDisconnectedAt, now);
        boolean blackOnline = computeOnline(presence.blackConnectionIds, presence.blackDisconnectedAt, now);
        if (presence.lastWhiteOnline != whiteOnline || presence.lastBlackOnline != blackOnline) {
            presence.lastWhiteOnline = whiteOnline;
            presence.lastBlackOnline = blackOnline;
            String payload = buildPresencePayloadOrNull(gameId, whiteOnline, blackOnline);
            if (payload != null) {
                for (Connection connection : connections.values()) {
                    if (gameId.equals(connection.gameId)) enqueueSend(connection, payload);
                }
            }
        }
    }

    /** Hands a container send off to {@link #presenceSender} instead of performing it on the
     * caller's thread (which, for every caller of this method, holds a game's presence lock -
     * see that field's javadoc for why a direct send there can deadlock). */
    private void enqueueSend(Connection connection, String payload) {
        try {
            presenceSender.execute(() -> send(connection, payload));
        } catch (RejectedExecutionException exception) {
            LOGGER.debug("Presence sender is shut down; dropping send to {}", connection.session.getId());
        }
    }

    /** Test hook: blocks until every presence send enqueued so far has been processed by
     * {@link #presenceSender} (which is FIFO), so a test can safely assert on what a mocked
     * session's {@code sendMessage} received right after triggering a presence change. */
    void awaitPresenceSends() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            presenceSender.execute(latch::countDown);
        } catch (RejectedExecutionException exception) {
            return;
        }
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** A JSON encoding failure must never throw out of sweepPresence() (it would abort the sweep for
     * every other game) or out of registerPresence(); log and return null instead, matching how the
     * ping job (sendPings()) handles a per-connection failure without ever throwing out of the loop. */
    private String buildPresencePayloadOrNull(UUID gameId, boolean whiteOnline, boolean blackOnline) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "type", "PRESENCE",
                    "white", whiteOnline ? "ONLINE" : "OFFLINE",
                    "black", blackOnline ? "ONLINE" : "OFFLINE"));
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Could not serialize presence notification for game {}", gameId, exception);
            return null;
        }
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
        presenceSender.shutdownNow();
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
        /** Set, under this instance's own lock, once sweepPresence() has unpublished this entry
         * from presenceByGame. A registerPresence() that observes this retries with a fresh
         * Presence rather than silently registering into an orphaned one (Important I1). */
        volatile boolean removed;
    }
}
