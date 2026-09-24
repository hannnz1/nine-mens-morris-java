package io.github.hannnz1.morris.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ClockView;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.PlayerCredential;
import io.github.hannnz1.morris.backend.api.GameApiDtos.RoomLookupResponse;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.GameListResponse;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.GameSummary;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordEntity;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerIdempotencyRecordEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerIdempotencyRecordRepository;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.GameAction;
import io.github.hannnz1.morris.engine.GameEngine;
import io.github.hannnz1.morris.engine.GameState;
import io.github.hannnz1.morris.engine.Player;
import org.springframework.http.HttpStatus;
import io.github.hannnz1.morris.backend.config.GameWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GameSessionService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GameSessionService.class);
    private static final List<String> ACTIVE_STATUSES = List.of("WAITING_FOR_PLAYER", "IN_PROGRESS");
    private static final List<String> FINISHED_STATUSES = List.of("WHITE_WON", "BLACK_WON", "DRAWN", "ABORTED");

    private final GameSessionRepository games;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final TokenService tokens;
    private final ObjectMapper objectMapper;
    private final GameWebSocketHandler gameUpdates;
    private final SeatResolver seatResolver;
    private final RoomCodeGenerator roomCodes;
    private final PlayerIdempotencyRecordRepository playerIdempotencyRecords;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final GameFinisher finisher;

    public GameSessionService(GameSessionRepository games,
                              IdempotencyRecordRepository idempotencyRecords,
                              TokenService tokens,
                              ObjectMapper objectMapper,
                              GameWebSocketHandler gameUpdates,
                              SeatResolver seatResolver,
                              RoomCodeGenerator roomCodes,
                              PlayerIdempotencyRecordRepository playerIdempotencyRecords,
                              RateLimiter rateLimiter,
                              Clock clock,
                              GameFinisher finisher) {
        this.games = games;
        this.idempotencyRecords = idempotencyRecords;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.gameUpdates = gameUpdates;
        this.seatResolver = seatResolver;
        this.roomCodes = roomCodes;
        this.playerIdempotencyRecords = playerIdempotencyRecords;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.finisher = finisher;
    }

    @Transactional
    public CreateGameResponse create(CreateGameRequest request) {
        String whiteToken = tokens.generate();
        Instant now = clock.instant();
        GameState state = GameEngine.newGame().state();
        GameSessionEntity entity = new GameSessionEntity(
                UUID.randomUUID(), request.whitePlayer().trim(),
                null,
                tokens.hash(whiteToken), "",
                "WAITING_FOR_PLAYER",
                writeJson(state), now);
        entity = games.saveAndFlush(entity);

        return new CreateGameResponse(
                toResponse(entity, state),
                new PlayerCredential(Player.WHITE, entity.getWhitePlayer(), whiteToken),
                null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JoinGameResponse join(UUID id, JoinGameRequest request) {
        GameSessionEntity entity = findGameForUpdate(id);
        // A Bearer/identity-created game (createForPlayer) has a null white_token_hash - it has no
        // legacy credential at all, so the anonymous join flow (self-generated joinToken, no
        // player identity) is not a valid way to claim its black seat: silently allowing it would
        // leave black_player_id null forever, breaking that player's "my games" list, and would
        // never let a real identity re-claim the seat. Reject cleanly instead of letting
        // TokenService.matches's null-hash guard (see M1 final review C1/I1) make this branch a
        // silent success. The frontend's join flow routes identity-aware joins through the Bearer
        // POST /join instead of this method when a player identity exists (see app.js joinGame).
        if (entity.getWhiteTokenHash() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PLAYER_TOKEN",
                    "This game requires a player identity to join; use the identity-aware join flow");
        }
        String blackToken = request.joinToken();
        if (tokens.matches(blackToken, entity.getWhiteTokenHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_JOIN_TOKEN", "Use an independent join credential");
        }
        if (entity.getBlackPlayer() != null && tokens.matches(blackToken, entity.getBlackTokenHash())) {
            return new JoinGameResponse(toResponse(entity, readState(entity)),
                    new PlayerCredential(Player.BLACK, entity.getBlackPlayer(), blackToken));
        }
        if (entity.getBlackPlayer() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_ALREADY_FULL",
                    "The game already has two players");
        }
        entity.joinBlackPlayer(request.blackPlayer().trim(), tokens.hash(blackToken), clock.instant());
        entity = games.saveAndFlush(entity);
        GameResponse response = toResponse(entity, readState(entity));
        publishAfterCommit(id, response);
        return new JoinGameResponse(response,
                new PlayerCredential(Player.BLACK, entity.getBlackPlayer(), blackToken));
    }

    @Transactional
    public CreateGameResponse createForPlayer(PlayerEntity white, String idempotencyKey, String timeControlLabel) {
        TimeControl timeControl = TimeControl.parse(timeControlLabel); // validate before any side effect
        // 20/min per player, per spec §3.5. Checked before the idempotency lookup so a retried
        // request under the same key is never itself penalized twice for the same logical create.
        if (!rateLimiter.tryAcquire("game-create:" + white.getId(), 20, java.time.Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many games created recently; try again shortly");
        }
        String fingerprint = tokens.hash(white.getId() + ":create:" + idempotencyKey);
        var previous = playerIdempotencyRecords.findByIdPlayerIdAndIdIdempotencyKey(white.getId(), idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), CreateGameResponse.class);
        }

        long activeGames = games.countActiveGamesForPlayer(white.getId(), ACTIVE_STATUSES);
        if (activeGames >= 5) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_ACTIVE_GAMES", "You already have 5 active or waiting games");
        }

        Instant now = clock.instant();
        GameState state = GameEngine.newGame().state();
        GameSessionEntity entity = new GameSessionEntity(UUID.randomUUID(), white.getNickname(), null,
                null, null, "WAITING_FOR_PLAYER", writeJson(state), now);
        entity.assignPlayers(white.getId(), null);
        entity.assignRoomCode(generateUniqueRoomCode());
        entity.setTimeControl(timeControl.baseMs(), timeControl.incrementMs()); // stores base/increment only; clock doesn't start until join
        entity = games.saveAndFlush(entity);

        CreateGameResponse response = new CreateGameResponse(toResponse(entity, state), null, entity.getRoomCode());
        playerIdempotencyRecords.saveAndFlush(new PlayerIdempotencyRecordEntity(
                white.getId(), idempotencyKey, fingerprint, writeJson(response), now));
        return response;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JoinGameResponse joinByBearer(UUID id, PlayerEntity black, String idempotencyKey) {
        String fingerprint = tokens.hash(black.getId() + ":join:" + id + ":" + idempotencyKey);
        var previous = playerIdempotencyRecords.findByIdPlayerIdAndIdIdempotencyKey(black.getId(), idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), JoinGameResponse.class);
        }

        GameSessionEntity entity = findGameForUpdate(id);
        if (black.getId().equals(entity.getWhitePlayerId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_JOIN_OWN_GAME", "Use a different browser or device to join as the other player");
        }
        if (black.getId().equals(entity.getBlackPlayerId())) {
            JoinGameResponse response = new JoinGameResponse(toResponse(entity, readState(entity)), null);
            return response;
        }
        if (entity.getBlackPlayerId() != null || entity.getBlackPlayer() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_ALREADY_FULL", "The game already has two players");
        }
        entity.assignPlayers(entity.getWhitePlayerId(), black.getId());
        entity.joinBlackPlayer(black.getNickname(), "", clock.instant());
        // A Bearer game created before M2 shipped has a NULL base_ms/increment_ms (createForPlayer
        // didn't call setTimeControl yet); fall back to the 5+3 default rather than unboxing null.
        // Pre-M2 games that were already IN_PROGRESS at deploy are untouched by this method (they
        // never re-enter join) and stay clockless forever, same as the anonymous join() path, which
        // never calls startClock at all.
        if (entity.getBaseMs() == null) {
            entity.setTimeControl(TimeControl.DEFAULT.baseMs(), TimeControl.DEFAULT.incrementMs());
        }
        entity.startClock(entity.getBaseMs(), entity.getIncrementMs(), clock.instant());
        entity = games.saveAndFlush(entity);
        JoinGameResponse response = new JoinGameResponse(toResponse(entity, readState(entity)), null);
        playerIdempotencyRecords.saveAndFlush(new PlayerIdempotencyRecordEntity(
                black.getId(), idempotencyKey, fingerprint, writeJson(response), clock.instant()));
        publishAfterCommit(id, response.game());
        return response;
    }

    @Transactional(readOnly = true)
    public RoomLookupResponse lookupRoom(String roomCode) {
        GameSessionEntity entity = games.findByRoomCodeAndStatusIn(roomCode, List.of("WAITING_FOR_PLAYER", "IN_PROGRESS"))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "No open game for this room code"));
        return new RoomLookupResponse(entity.getId(), entity.getStatus(), entity.getWhitePlayer());
    }

    @Transactional(readOnly = true)
    public GameListResponse listForPlayer(UUID playerId, String statusFilter, Instant before, int limit) {
        List<String> statuses = "FINISHED".equals(statusFilter) ? FINISHED_STATUSES : ACTIVE_STATUSES;
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        var pageable = org.springframework.data.domain.PageRequest.of(0, boundedLimit);
        List<GameSessionEntity> rows = before == null
                ? games.findForPlayer(playerId, statuses, pageable)
                : games.findForPlayerBefore(playerId, statuses, before, pageable);
        List<GameSummary> summaries = rows.stream()
                .map(row -> new GameSummary(row.getId(), row.getStatus(),
                        playerId.equals(row.getWhitePlayerId()) ? row.getBlackPlayer() : row.getWhitePlayer(),
                        row.getUpdatedAt()))
                .toList();
        Instant nextBefore = summaries.size() == boundedLimit ? summaries.get(summaries.size() - 1).updatedAt() : null;
        return new GameListResponse(summaries, nextBefore);
    }

    private String generateUniqueRoomCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = roomCodes.generate();
            if (games.findByRoomCodeAndStatusIn(candidate, List.of("WAITING_FOR_PLAYER", "IN_PROGRESS")).isEmpty()) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Could not allocate a room code, try again");
    }

    @Transactional(readOnly = true)
    public GameResponse get(UUID id) {
        GameSessionEntity entity = findGame(id);
        return toResponse(entity, readState(entity));
    }

    @Transactional(readOnly = true)
    public GameResponse restore(UUID id, String bearerToken, String legacySeatToken) {
        GameSessionEntity entity = findGame(id);
        seatResolver.resolve(entity, bearerToken, legacySeatToken);
        return toResponse(entity, readState(entity));
    }

    // Per spec M2.3: this method never throws to signal a clock-timeout rejection - it returns an
    // ActionOutcome (rejectedByTimeout = true on that path) after committing the TIMEOUT verdict via
    // GameFinisher.finish, entirely inside this method's own transaction. GameController.action is
    // the one that turns rejectedByTimeout into an HTTP 409 GAME_NOT_ACTIVE, by which point this
    // transaction has already committed - so there is no throw-after-write path in this method that
    // would need noRollbackFor.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionOutcome performAction(UUID id, String bearerToken, String legacySeatToken, String idempotencyKey,
                                      ActionRequest request) {
        validateIdempotencyKey(idempotencyKey);
        // Mirrors SeatResolver.resolve's own branch-selection predicate exactly (usable: non-blank
        // AND within the token length cap), not a bare != null or isBlank check, so the idempotency
        // fingerprint is always hashed from the same credential slot SeatResolver tries first -
        // whether the bearer token is absent, blank, or oversized.
        String credential = SeatResolver.usable(bearerToken) ? bearerToken : legacySeatToken;
        String fingerprint = tokens.hash(credential + ":" + writeJson(request));

        GameSessionEntity entity = findGameForUpdate(id);
        Player player = seatResolver.resolve(entity, bearerToken, legacySeatToken);
        // Read after acquiring the game lock so concurrent retries see the committed result.
        // Replay before checking the version/turn, which change after a successful action.
        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            // The stored JSON is an ActionOutcome (not a bare GameResponse) precisely so a retried
            // request after a timeout rejection replays as rejectedByTimeout = true too, not a 200.
            ActionOutcome outcome = readJson(previous.get().getResponseJson(), ActionOutcome.class);
            if (outcome.game() == null) {
                // idempotency_records has existed since V1: a record written before this ActionOutcome
                // wrapper shipped holds a bare GameResponse. Jackson silently ignores the unknown
                // "game"/"rejectedByTimeout" properties and would otherwise hand back
                // ActionOutcome(null, false) - a 200 with an empty body. Re-read the same JSON as the
                // old shape and wrap it instead.
                outcome = new ActionOutcome(readJson(previous.get().getResponseJson(), GameResponse.class), false);
            }
            return outcome;
        }

        if (entity.getBlackPlayer() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WAITING_FOR_PLAYER",
                    "A second player must join before the game can start");
        }
        // Guards against re-entering the clock-timeout/mill/no-moves branches below a second time
        // for a game GameFinisher.finish already closed out: after a TIMEOUT or ABORTED finish,
        // state_json still has no engine winner and turnDeadlineAt stays in the past, so a fresh
        // request (new idempotency key, current version) would otherwise re-enter the timeout check
        // and call finisher.finish again, rewriting the result and re-broadcasting it.
        if (!"IN_PROGRESS".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "This game is not in progress");
        }
        GameState currentState = readState(entity);
        if (request.expectedVersion() != entity.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "The supplied version is stale; reload the game before retrying");
        }
        if (player != currentState.currentPlayer()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_TURN",
                    "Only the current player can perform this action");
        }

        // Clock settlement, per spec M2.3's pseudocode. entity.getTurnDeadlineAt() is null only for
        // a pre-M2 game (legacy anonymous create/join never calls startClock) - such games have no
        // clock at all and skip this whole block.
        boolean hasClock = entity.getTurnDeadlineAt() != null;
        boolean whiteToMove = player == Player.WHITE;
        long elapsedMs = 0;
        long remainingBefore = 0;
        if (hasClock) {
            Instant timeoutCheckNow = clock.instant();
            if (!timeoutCheckNow.isBefore(entity.getTurnDeadlineAt())) {
                // The mover's own clock had already reached zero before this action arrived: the
                // action is never applied. Per spec M2.3's first-move grace, a late FIRST move ends
                // the game as ABORTED (winner null) rather than a TIMEOUT loss - ClockTimeoutOutcome
                // is the single place that decision is made, shared with Task 6's background
                // TimeoutScanner so the two paths can never disagree. finisher.finish commits the
                // verdict inside this transaction either way; rejectedByTimeout = true tells
                // GameController.action to turn this into an HTTP 409 GAME_NOT_ACTIVE, per spec
                // M2.3's "服务方法返回一个「结果对象」,由 Controller 转换为 409 响应,而不是在事务内抛异常".
                var expiry = ClockTimeoutOutcome.forExpiry(currentState);
                GameResponse timedOut = finisher.finish(entity, expiry.winner(), expiry.reason());
                ActionOutcome outcome = new ActionOutcome(timedOut, true);
                idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                        UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(outcome), timeoutCheckNow));
                return outcome;
            }
            elapsedMs = Duration.between(entity.getTurnStartedAt(), timeoutCheckNow).toMillis();
            remainingBefore = whiteToMove ? entity.getWhiteRemainingMs() : entity.getBlackRemainingMs();
        }
        long remainingAfter = remainingBefore - elapsedMs;

        GameState nextState = GameEngine.restore(currentState)
                .apply(new GameAction(request.type(), request.from(), request.to()));

        if (nextState.winner() != null || nextState.drawReason() != null) {
            entity.updateState(statusOf(nextState), writeJson(nextState), clock.instant());
            entity = games.saveAndFlush(entity);
            GameResponse finishedResponse = finisher.finish(entity, nextState.winner(),
                    nextState.winner() != null ? engineWinReason(nextState) : nextState.drawReason());
            ActionOutcome outcome = new ActionOutcome(finishedResponse, false);
            idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                    UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(outcome), clock.instant()));
            return outcome;
        }

        if (hasClock) {
            boolean handoff = nextState.currentPlayer() != currentState.currentPlayer(); // turn passed, not still removing
            // Per spec M2.3: a side's own first move (piecesToPlace == 9 in the state before that
            // move) deducts no main time and adds no increment - the mover's remaining time stays at
            // baseMs (remainingBefore, since nothing has been subtracted from it yet). A first move
            // can never form a mill, so this branch and the terminal one above never overlap.
            boolean moverFirstMove = currentState.piecesToPlace(player) == 9;
            long settledMs = moverFirstMove ? remainingBefore
                    : (handoff ? remainingAfter + entity.getIncrementMs() : remainingAfter);
            long opponentRemaining = whiteToMove ? entity.getBlackRemainingMs() : entity.getWhiteRemainingMs();
            // When the turn hands off to a side about to make ITS first move, that side's deadline is
            // now + 30s grace (not its remaining time), per spec M2.3 - independent of whether the
            // mover who just moved was itself in its own first move.
            boolean nextIsFirstMove = handoff && nextState.piecesToPlace(nextState.currentPlayer()) == 9;
            long nextDeadlineBudgetMs = !handoff ? settledMs : (nextIsFirstMove ? 30_000L : opponentRemaining);
            if (whiteToMove) {
                entity.settleClock(settledMs, entity.getBlackRemainingMs(), clock.instant(), nextDeadlineBudgetMs);
            } else {
                entity.settleClock(entity.getWhiteRemainingMs(), settledMs, clock.instant(), nextDeadlineBudgetMs);
            }
        }

        entity.updateState(statusOf(nextState), writeJson(nextState), clock.instant());
        entity = games.saveAndFlush(entity);
        GameResponse response = toResponse(entity, nextState);
        ActionOutcome outcome = new ActionOutcome(response, false);

        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(outcome), clock.instant()));
        publishAfterCommit(id, response);
        return outcome;
    }

    private String engineWinReason(GameState state) {
        Player loser = state.winner().opponent();
        return state.piecesOnBoard(loser) < 3 ? "NO_PIECES" : "NO_MOVES";
    }

    private GameSessionEntity findGame(UUID id) {
        return games.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "The game does not exist"));
    }

    private GameSessionEntity findGameForUpdate(UUID id) {
        return games.findByIdForUpdate(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "The game does not exist"));
    }

    private GameResponse toResponse(GameSessionEntity entity, GameState state) {
        GameEngine engine = GameEngine.restore(state);
        Map<BoardPosition, List<BoardPosition>> legalMoves = new LinkedHashMap<>();
        engine.legalMoves().forEach((source, destinations) ->
                legalMoves.put(source, List.copyOf(destinations)));
        return new GameResponse(entity.getId(), entity.getVersion(), entity.getWhitePlayer(),
                entity.getBlackPlayer(), entity.getStatus(), state.phase(), state,
                List.copyOf(engine.legalPlacements()), legalMoves, List.copyOf(engine.removablePieces()),
                entity.getCreatedAt(), entity.getUpdatedAt(), clockView(entity));
    }

    private ClockView clockView(GameSessionEntity entity) {
        if (entity.getTurnDeadlineAt() == null) {
            return new ClockView(0, 0, false, clock.instant()); // pre-clock game (WAITING, or pre-M2)
        }
        return new ClockView(entity.getWhiteRemainingMs(), entity.getBlackRemainingMs(),
                "IN_PROGRESS".equals(entity.getStatus()), clock.instant());
    }

    private GameState readState(GameSessionEntity entity) {
        return readJson(entity.getStateJson(), GameState.class);
    }

    // Package-visible wrapper around readState so TimeoutScanner can obtain the GameState it needs
    // to call ClockTimeoutOutcome.forExpiry without a second Jackson deserialization path (Task 6).
    GameState stateOf(GameSessionEntity entity) {
        return readState(entity);
    }

    private String statusOf(GameState state) {
        return state.winner() == null ? "IN_PROGRESS" : state.winner().name() + "_WON";
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain between 1 and 100 characters");
        }
    }

    private void publishAfterCommit(UUID gameId, GameResponse response) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    gameUpdates.broadcast(gameId, response);
                } catch (RuntimeException exception) {
                    // The transaction is already committed; snapshot reads recover missed notifications.
                    LOGGER.warn("Committed game {} version {} could not be broadcast", gameId, response.version(), exception);
                }
            }
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize game data", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize game data", exception);
        }
    }
}
