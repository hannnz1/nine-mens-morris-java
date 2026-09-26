package io.github.hannnz1.morris.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.BotGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.PlayerColor;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ClockView;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.JoinGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.PlayerCredential;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ResultView;
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
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
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
    private static final List<String> FINISHED_STATUSES = List.of("WHITE_WON", "BLACK_WON", "DRAWN", "ABORTED", "CANCELLED");
    private static final Duration REMATCH_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_DRAW_OFFERS = 3;

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
    private final PlayerRepository playerRepository;
    private final BotCapacityGuard botCapacity;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final java.security.SecureRandom botColors = new java.security.SecureRandom();

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
                              GameFinisher finisher,
                              PlayerRepository playerRepository, BotCapacityGuard botCapacity,
                              org.springframework.context.ApplicationEventPublisher events) {
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
        this.playerRepository = playerRepository;
        this.botCapacity = botCapacity;
        this.events = events;
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateGameResponse createForPlayer(PlayerEntity white, String idempotencyKey, String timeControlLabel) {
        TimeControl timeControl = TimeControl.parse(timeControlLabel); // validate before any side effect
        lockHumanPlayers(white);
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

        requireHumanCapacity(white);

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
    public CreateGameResponse createBotGame(PlayerEntity human, String idempotencyKey, BotGameRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (!"HUMAN".equals(human.getKind()) || request.difficulty() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Human and difficulty required");
        var time = TimeControl.parse(request.timeControl());
        var color = request.color() == null ? PlayerColor.RANDOM : request.color();
        String fingerprint = tokens.hash(human.getId() + ":create:BOT:" + request.difficulty() + ":" + color + ":" + time);
        botCapacity.lock(); // held through replay, capacity check and transaction commit
        lockHumanPlayers(human);
        var previous = playerIdempotencyRecords.findByIdPlayerIdAndIdIdempotencyKey(human.getId(), idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Key used for a different request");
            return readJson(previous.get().getResponseJson(), CreateGameResponse.class);
        }
        if (!rateLimiter.tryAcquire("game-create:" + human.getId(), 20, Duration.ofMinutes(1)))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many games created recently");
        requireHumanCapacity(human);
        botCapacity.requireAvailable();
        var bot = playerByIdOrThrow(BotRoster.id(request.difficulty()));
        boolean humanWhite = color == PlayerColor.WHITE || (color == PlayerColor.RANDOM && botColors.nextBoolean());
        var white = humanWhite ? human : bot;
        var black = humanWhite ? bot : human;
        var state = GameEngine.newGame().state();
        var now = clock.instant();
        var entity = new GameSessionEntity(UUID.randomUUID(), white.getNickname(), black.getNickname(), null, null,
                "IN_PROGRESS", writeJson(state), now);
        entity.assignPlayers(white.getId(), black.getId());
        entity.setTimeControl(time.baseMs(), time.incrementMs());
        entity.startClock(time.baseMs(), time.incrementMs(), now);
        entity = games.saveAndFlush(entity);
        var response = new CreateGameResponse(toResponse(entity, state), null, null);
        playerIdempotencyRecords.saveAndFlush(new PlayerIdempotencyRecordEntity(human.getId(), idempotencyKey, fingerprint, writeJson(response), now));
        publishAfterCommit(entity.getId(), response.game());
        return response;
    }

    // Admission lock order: existing game (if any), global bot capacity (if needed),
    // then human UUIDs in ascending order. Counts are read after acquiring all player locks.
    private void lockHumanPlayers(PlayerEntity... players) {
        java.util.Arrays.stream(players).map(PlayerEntity::getId).filter(id -> !BotRoster.isBot(id))
                .distinct().sorted().forEach(id -> playerRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new IllegalStateException("Player no longer exists: " + id)));
    }

    private void requireHumanCapacity(PlayerEntity player) {
        if (!BotRoster.isBot(player.getId()) && games.countActiveGamesForPlayer(player.getId(), ACTIVE_STATUSES) >= 5)
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_ACTIVE_GAMES", "A player already has 5 active or waiting games");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JoinGameResponse joinByBearer(UUID id, PlayerEntity black, String idempotencyKey) {
        String fingerprint = tokens.hash(black.getId() + ":join:" + id + ":" + idempotencyKey);
        // Pattern A: lock first, so a concurrent retry under the same key serializes behind the
        // first request and then replays its committed record.
        GameSessionEntity entity = findGameForUpdate(id);
        lockHumanPlayers(black);
        var previous = playerIdempotencyRecords.findByIdPlayerIdAndIdIdempotencyKey(black.getId(), idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), JoinGameResponse.class);
        }

        if (black.getId().equals(entity.getWhitePlayerId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_JOIN_OWN_GAME", "Use a different browser or device to join as the other player");
        }
        if (black.getId().equals(entity.getBlackPlayerId())) {
            // The seated black player re-entering (e.g. via the room code again): a pure read that
            // writes nothing, so it is allowed in any status.
            JoinGameResponse response = new JoinGameResponse(toResponse(entity, readState(entity)), null);
            return response;
        }
        // Seat check first: a third player on a game with both seats taken is told it is full,
        // whatever its status - the more specific and actionable answer.
        if (entity.getBlackPlayerId() != null || entity.getBlackPlayer() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_ALREADY_FULL", "The game already has two players");
        }
        // Only a game still waiting for its second player can be joined. Without this, a CANCELLED
        // game (which has no black player) passed the seat check above and was brought back to
        // life as IN_PROGRESS with a running clock (final-review C2).
        if (!"WAITING_FOR_PLAYER".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "This game can no longer be joined");
        }
        requireHumanCapacity(black);
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
        Instant now = clock.instant();
        List<GameSummary> summaries = rows.stream()
                .map(row -> {
                    boolean isWhite = playerId.equals(row.getWhitePlayerId());
                    boolean rematchOpen = isRematchOpen(row, now);
                    String opponentSide = isWhite ? "BLACK" : "WHITE";
                    return new GameSummary(row.getId(), row.getStatus(),
                            isWhite ? row.getBlackPlayer() : row.getWhitePlayer(),
                            row.getUpdatedAt(), rematchOpen,
                            rematchOpen && row.hasPendingRematchOfferFrom(opponentSide));
                })
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
        return applyAction(entity, player, id, idempotencyKey, fingerprint, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionOutcome performBotAction(UUID id, UUID botPlayerId, ActionRequest request) {
        if (!BotRoster.isBot(botPlayerId) || request == null || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw new IllegalArgumentException("A seated bot and expected version are required");
        GameSessionEntity entity = findGameForUpdate(id);
        Player player = botPlayerId.equals(entity.getWhitePlayerId()) ? Player.WHITE
                : botPlayerId.equals(entity.getBlackPlayerId()) ? Player.BLACK : null;
        if (player == null) throw new IllegalArgumentException("Bot is not seated in this game");
        String key = "bot:" + id + ":" + request.expectedVersion();
        String fingerprint = tokens.hash("bot:" + botPlayerId + ":" + request.expectedVersion());
        return applyAction(entity, player, id, key, fingerprint, request);
    }

    private ActionOutcome applyAction(GameSessionEntity entity, Player player, UUID id, String idempotencyKey,
                                      String fingerprint, ActionRequest request) {
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

        // Decided by status, not by "black seat empty": a CANCELLED game also has no black player,
        // and must report GAME_NOT_ACTIVE rather than WAITING_FOR_PLAYER (final-review M-h).
        if ("WAITING_FOR_PLAYER".equals(entity.getStatus())) {
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
            // currentState (not the just-stored nextState) tells the finisher whose clock was
            // running: after a winning or drawing move, nextState's side to move may already be the
            // opponent, but the elapsed turn time belongs to the mover.
            GameResponse finishedResponse = finisher.finish(entity, nextState.winner(),
                    nextState.winner() != null ? engineWinReason(nextState) : nextState.drawReason(),
                    currentState);
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

        // Per spec M2.6 ("对方走了一步,就视为拒绝"): a legally-applied move by either side auto-clears
        // any pending draw offer while the game continues. A pending offer only ever belongs to one
        // side, so this covers both the responder playing on (declining) and the offerer themselves
        // moving after their own offer.
        if (entity.getDrawOfferedBy() != null) {
            entity.clearDrawOffer();
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

    // Resign accepts only the Bearer credential (legacySeatToken passed as null to SeatResolver):
    // pre-M1 anonymous games have no player ids, so those callers get 403 INVALID_PLAYER_TOKEN,
    // per the plan's Global Constraints. The idempotency fingerprint's ":resign:" segment is a
    // discriminator that keeps a key reused across /resign, /cancel, or /actions from ever
    // replaying the wrong stored shape - it fails the fingerprint match instead.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GameResponse resign(UUID id, String bearerToken, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = tokens.hash(bearerToken + ":resign:" + idempotencyKey);
        GameSessionEntity entity = findGameForUpdate(id);
        Player player = seatResolver.resolve(entity, bearerToken, null);

        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), GameResponse.class);
        }
        if (!"IN_PROGRESS".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "The game is not active");
        }

        // GameFinisher.finish already commits (saveAndFlush) and schedules the WebSocket broadcast
        // after commit itself - resign must not go through any other write path per the plan's
        // Global Constraints.
        GameResponse response = finisher.finish(entity, player.opponent(), "RESIGN");
        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(response), clock.instant()));
        return response;
    }

    // Cancel accepts only the Bearer credential (legacySeatToken passed as null), same as resign.
    // Only the creator (WHITE) may cancel, and only while the game is still WAITING_FOR_PLAYER -
    // once black has joined, the game must be resigned or played out, not cancelled. Cancel
    // deliberately does NOT go through GameFinisher: a cancelled game never reached IN_PROGRESS, so
    // it has no result to record (see GameSessionEntity.cancel).
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GameResponse cancel(UUID id, String bearerToken, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = tokens.hash(bearerToken + ":cancel:" + idempotencyKey);
        GameSessionEntity entity = findGameForUpdate(id);
        Player player = seatResolver.resolve(entity, bearerToken, null);
        if (player != Player.WHITE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PLAYER_TOKEN", "Only the creator can cancel");
        }

        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), GameResponse.class);
        }
        if (!"WAITING_FOR_PLAYER".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "The game already has a second player");
        }

        entity.cancel(clock.instant());
        entity = games.saveAndFlush(entity);
        GameResponse response = toResponse(entity, readState(entity));
        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(response), clock.instant()));
        publishAfterCommit(id, response);
        return response;
    }

    // Draw offer/accept/decline accepts only the Bearer credential (legacySeatToken passed as
    // null), same as resign/cancel. The fingerprint's ":draw:" discriminator plus the action keeps
    // a key reused across /draw, /resign, /cancel, or /actions from ever replaying the wrong stored
    // shape - it fails the fingerprint match instead. The idempotency replay happens before the
    // IN_PROGRESS status check (per spec Pattern A) so a retried request against an already-finished
    // (e.g. drawn) game still replays its original 200 instead of a fresh 409.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GameResponse offerDraw(UUID id, String bearerToken, String idempotencyKey, String action) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = tokens.hash(bearerToken + ":draw:" + idempotencyKey + ":" + action);
        GameSessionEntity entity = findGameForUpdate(id);
        Player player = seatResolver.resolve(entity, bearerToken, null);
        String side = player.name();
        String opponentSide = player.opponent().name();

        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), GameResponse.class);
        }
        if (!"IN_PROGRESS".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "The game is not active");
        }

        GameResponse response = switch (action) {
            case "OFFER" -> {
                if (BotRoster.side(entity.getWhitePlayerId(), entity.getBlackPlayerId()) != null)
                    throw new ApiException(HttpStatus.CONFLICT, "DRAW_NOT_AVAILABLE", "Draw offers are unavailable against a computer");
                if (entity.hasPendingDrawOfferFrom(opponentSide)) {
                    // Both sides offered around the same time; the second request serializes behind
                    // the row lock and sees the first offer already pending - treat it as an accept.
                    yield finishAsDraw(entity);
                }
                if (entity.hasPendingDrawOfferFrom(side)) {
                    yield toResponse(entity, readState(entity)); // idempotent no-op, already offered
                }
                if (entity.drawOffersUsedBy(side) >= MAX_DRAW_OFFERS) {
                    throw new ApiException(HttpStatus.CONFLICT, "OFFER_LIMIT_REACHED",
                            "You have already offered a draw the maximum number of times");
                }
                entity.offerDraw(side, clock.instant());
                entity = games.saveAndFlush(entity);
                GameResponse offered = toResponse(entity, readState(entity));
                publishAfterCommit(id, offered);
                yield offered;
            }
            case "ACCEPT" -> {
                if (!entity.hasPendingDrawOfferFrom(opponentSide)) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_PENDING_OFFER", "There is no pending draw offer to accept");
                }
                yield finishAsDraw(entity);
            }
            case "DECLINE" -> {
                if (!entity.hasPendingDrawOfferFrom(opponentSide)) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_PENDING_OFFER", "There is no pending draw offer to decline");
                }
                entity.clearDrawOffer();
                entity = games.saveAndFlush(entity);
                GameResponse declined = toResponse(entity, readState(entity));
                publishAfterCommit(id, declined);
                yield declined;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "action must be OFFER, ACCEPT, or DECLINE");
        };

        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(response), clock.instant()));
        return response;
    }

    // ACCEPT (and the "both sides offered at once" OFFER-treated-as-accept branch) finish through
    // GameFinisher.finish, which is the only terminal-write path - it already commits (saveAndFlush)
    // and schedules the post-commit WebSocket broadcast itself, so offerDraw's own
    // idempotencyRecords.saveAndFlush below still runs (to record the *draw offer's* idempotency
    // key against the terminal response), but must not publish a second time.
    private GameResponse finishAsDraw(GameSessionEntity entity) {
        return finisher.finish(entity, null, "DRAW_AGREED");
    }

    // Rematch accepts only the Bearer credential (legacySeatToken passed as null), same as
    // resign/draw. The fingerprint's ":rematch:" discriminator plus the action keeps a key reused
    // across /rematch, /draw, /resign, or /actions from ever replaying the wrong stored shape.
    // Ordering (Pattern A, mirrors offerDraw): validate key -> fingerprint -> lock row -> resolve
    // seat -> idempotency replay -> eligibility -> action logic -> idempotency record write.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GameResponse offerRematch(UUID id, String bearerToken, String idempotencyKey, String action) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = tokens.hash(bearerToken + ":rematch:" + idempotencyKey + ":" + action);
        GameSessionEntity entity = findGameForUpdate(id);
        Player player = seatResolver.resolve(entity, bearerToken, null);
        String side = player.name();
        String opponentSide = player.opponent().name();

        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), GameResponse.class);
        }
        // Once a rematch game already exists, any further call (from either side, any action)
        // replays the original's current response idempotently, per spec M2.6 - this check runs
        // before the eligibility checks below, since a finished+linked original would otherwise
        // fail the (still-applicable) status/window checks even though the rematch already happened.
        if (entity.getRematchGameId() != null) {
            GameResponse existing = toResponse(entity, readState(entity));
            idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                    UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(existing), clock.instant()));
            return existing;
        }
        if (!isRematchOpen(entity, clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ACTIVE", "This game cannot be rematched");
        }

        GameResponse response = switch (action) {
            case "OFFER" -> {
                if (BotRoster.side(entity.getWhitePlayerId(), entity.getBlackPlayerId()) != null)
                    yield createRematchGame(entity);
                if (entity.hasPendingRematchOfferFrom(opponentSide)) {
                    // Both sides asked for a rematch around the same time; the second request
                    // serializes behind the row lock and sees the first offer already pending -
                    // treat it as an accept so exactly one new game is created.
                    yield createRematchGame(entity);
                }
                if (entity.hasPendingRematchOfferFrom(side)) {
                    yield toResponse(entity, readState(entity)); // idempotent no-op, already offered
                }
                entity.offerRematch(side, clock.instant());
                entity = games.saveAndFlush(entity);
                GameResponse offered = toResponse(entity, readState(entity));
                publishAfterCommit(id, offered);
                yield offered;
            }
            case "ACCEPT" -> {
                if (!entity.hasPendingRematchOfferFrom(opponentSide)) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_PENDING_OFFER", "There is no pending rematch offer to accept");
                }
                yield createRematchGame(entity);
            }
            case "DECLINE" -> {
                if (!entity.hasPendingRematchOfferFrom(opponentSide)) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_PENDING_OFFER", "There is no pending rematch offer to decline");
                }
                entity.offerRematch(null, clock.instant());
                entity = games.saveAndFlush(entity);
                GameResponse declined = toResponse(entity, readState(entity));
                publishAfterCommit(id, declined);
                yield declined;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "action must be OFFER, ACCEPT, or DECLINE");
        };

        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(response), clock.instant()));
        return response;
    }

    // Creates the rematch game in the same transaction as the caller (offerRematch), so throwing
    // (e.g. TOO_MANY_ACTIVE_GAMES) rolls back any partial write and leaves the original's
    // rematchOfferedBy untouched, per spec ("旧对局的 OFFER 保持不变"). Colors swap unconditionally:
    // the new game's WHITE is whoever was BLACK in the original, and vice versa - this holds no
    // matter which side calls ACCEPT, or which side's OFFER triggers the "both offered" path, so it
    // does not depend on `acceptingPlayer` at all.
    // A rematch can still be offered/accepted: a decided identity game, finished less than 5 minutes
    // ago, with no rematch game created yet. Shared by /rematch and the 我的对局 summaries.
    private static boolean isRematchOpen(GameSessionEntity game, Instant now) {
        return List.of("WHITE_WON", "BLACK_WON", "DRAWN").contains(game.getStatus())
                && game.getWhitePlayerId() != null && game.getBlackPlayerId() != null
                && game.getRematchGameId() == null
                && game.getFinishedAt() != null
                && now.isBefore(game.getFinishedAt().plus(REMATCH_WINDOW));
    }

    private GameResponse createRematchGame(GameSessionEntity original) {
        PlayerEntity newWhite = playerByIdOrThrow(original.getBlackPlayerId());
        PlayerEntity newBlack = playerByIdOrThrow(original.getWhitePlayerId());

        boolean botGame = BotRoster.side(newWhite.getId(), newBlack.getId()) != null;
        if (botGame) botCapacity.requireAvailable();
        lockHumanPlayers(newWhite, newBlack);
        requireHumanCapacity(newWhite);
        requireHumanCapacity(newBlack);

        TimeControl timeControl = original.getBaseMs() != null && original.getIncrementMs() != null
                ? new TimeControl(original.getBaseMs(), original.getIncrementMs())
                : TimeControl.DEFAULT;

        GameState state = GameEngine.newGame().state();
        Instant now = clock.instant();
        GameSessionEntity newGame = new GameSessionEntity(UUID.randomUUID(), newWhite.getNickname(),
                newBlack.getNickname(), null, null, "IN_PROGRESS", writeJson(state), now);
        newGame.assignPlayers(newWhite.getId(), newBlack.getId());
        if (!botGame) newGame.assignRoomCode(generateUniqueRoomCode());
        newGame.setTimeControl(timeControl.baseMs(), timeControl.incrementMs());
        newGame.startClock(timeControl.baseMs(), timeControl.incrementMs(), now); // White's 30s first-move grace starts immediately
        newGame = games.saveAndFlush(newGame);

        original.linkRematchGame(newGame.getId(), now);
        original = games.saveAndFlush(original);

        GameResponse newGameResponse = toResponse(newGame, state);
        publishAfterCommit(newGame.getId(), newGameResponse);
        GameResponse originalResponse = toResponse(original, readState(original));
        publishAfterCommit(original.getId(), originalResponse); // so the original game's viewers see rematchGameId too
        return originalResponse; // caller reads rematchGameId from this
    }

    private PlayerEntity playerByIdOrThrow(UUID playerId) {
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalStateException("Player referenced by a game no longer exists: " + playerId));
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
                entity.getCreatedAt(), entity.getUpdatedAt(), clockView(entity),
                entity.getDrawOfferedBy(), resultView(entity),
                entity.getRematchOfferedBy(), entity.getRematchGameId(),
                entity.getWhitePlayerId(), entity.getBlackPlayerId(),
                TimeControl.label(entity.getBaseMs(), entity.getIncrementMs()),
                BotRoster.side(entity.getWhitePlayerId(), entity.getBlackPlayerId()),
                BotRoster.difficulty(entity.getWhitePlayerId(), entity.getBlackPlayerId()));
    }

    private ResultView resultView(GameSessionEntity entity) {
        if (entity.getResultReason() == null) {
            return null;
        }
        return new ResultView(entity.getResultWinner(), entity.getResultReason());
    }

    private ClockView clockView(GameSessionEntity entity) {
        if (entity.getTurnDeadlineAt() == null) {
            return new ClockView(0, 0, false, clock.instant(), null); // pre-clock game (WAITING, or pre-M2)
        }
        // The stored deadline is kept after a finish (a non-null deadline is what marks a clocked
        // game), but a stopped clock has no turn deadline to show.
        boolean running = "IN_PROGRESS".equals(entity.getStatus());
        return new ClockView(entity.getWhiteRemainingMs(), entity.getBlackRemainingMs(),
                running, clock.instant(), running ? entity.getTurnDeadlineAt() : null);
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
        if (key.startsWith("bot:")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "The bot: prefix is reserved for internal bot actions");
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
                try { events.publishEvent(new GameCommittedEvent(response)); }
                catch (RuntimeException exception) { LOGGER.warn("Committed game {} could not schedule a bot", gameId, exception); }
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
