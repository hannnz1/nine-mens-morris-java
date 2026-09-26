package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.ClockView;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ResultView;
import io.github.hannnz1.morris.backend.config.GameWebSocketHandler;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.GameEngine;
import io.github.hannnz1.morris.engine.GameState;
import io.github.hannnz1.morris.engine.Player;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single entry point for ending an ACTIVE game: mill/no-moves win, any draw, timeout, or
 * resignation all call this - never {@code entity.updateState}/{@code entity.finish} directly.
 * (The only other terminal write is {@code GameSessionService.cancel}, for a game that never
 * started.)
 *
 * <p>Caller must already hold the row lock (via {@code findByIdForUpdate}) and be inside the write
 * transaction that will commit the result - this method does not take the lock, open a transaction
 * or commit. It does, however, write within the caller's transaction: it settles the clock of the
 * side to move, records the result, clears any pending draw offer, {@code saveAndFlush}es the
 * entity, and registers an after-commit synchronization that broadcasts the final
 * {@code GAME_STATE} once the caller's transaction commits (nothing is broadcast on rollback).
 */
@Component
public class GameFinisher {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GameFinisher.class);

    private final GameSessionRepository games;
    private final GameWebSocketHandler gameUpdates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;

    public GameFinisher(GameSessionRepository games, GameWebSocketHandler gameUpdates,
                         ObjectMapper objectMapper, Clock clock, org.springframework.context.ApplicationEventPublisher events) {
        this.games = games;
        this.gameUpdates = gameUpdates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Ends the game. The side whose clock is settled is the side to move in the entity's current
     * stored state - correct for timeouts, resignation and agreed draws, where the stored state is
     * still the one the running turn started from.
     */
    public GameResponse finish(GameSessionEntity entity, Player winner, String reason) {
        return finish(entity, winner, reason, readState(entity));
    }

    /**
     * Same as {@link #finish(GameSessionEntity, Player, String)}, but settles the clock of the side
     * to move in {@code turnState} instead of in the stored state. {@code performAction}'s
     * engine-terminal branch (mill win, no-moves win, repetition/no-capture draw) has already
     * written the post-move state - whose {@code currentPlayer} may be the opponent - so it passes
     * the pre-move state here, whose side to move is the player whose turn the clock was timing.
     */
    GameResponse finish(GameSessionEntity entity, Player winner, String reason, GameState turnState) {
        String status = switch (reason) {
            case "DRAW_REPETITION", "DRAW_NO_CAPTURE", "DRAW_AGREED" -> "DRAWN";
            case "ABORTED" -> "ABORTED";
            default -> winner.name() + "_WON";
        };
        Instant now = clock.instant();
        settleClockOfSideToMove(entity, turnState, now);
        entity.finish(status, winner == null ? null : winner.name(), reason, now); // also clears drawOfferedBy
        entity = games.saveAndFlush(entity);

        GameState state = readState(entity);
        GameResponse response = toResponse(entity, state);
        publishAfterCommit(entity.getId(), response);
        return response;
    }

    // Stops the clock at the right value (final-review I2). Without this, the final snapshot shows
    // the side to move's time as of the START of its last turn: a TIMEOUT loser would still show
    // its whole remaining budget, and a mid-turn resignation would show no time used. Per spec
    // M2.3, a side's first move consumes no main time, so its remaining is left untouched (this
    // also covers ABORTED). Otherwise the elapsed turn time - capped at the deadline, so a late
    // finish (scanner lag) can never overdraw - is deducted, floored at 0. No increment: the turn
    // never handed off. Clockless legacy games (turnDeadlineAt null) are skipped entirely.
    private void settleClockOfSideToMove(GameSessionEntity entity, GameState turnState, Instant now) {
        Instant deadline = entity.getTurnDeadlineAt();
        Instant turnStartedAt = entity.getTurnStartedAt();
        if (deadline == null || turnStartedAt == null) {
            return;
        }
        Player mover = turnState.currentPlayer();
        if (turnState.piecesToPlace(mover) == 9) {
            return; // first move: main time is never consumed
        }
        Long remaining = mover == Player.WHITE ? entity.getWhiteRemainingMs() : entity.getBlackRemainingMs();
        if (remaining == null) {
            return;
        }
        Instant stoppedAt = now.isBefore(deadline) ? now : deadline;
        long elapsedMs = Math.max(0, Duration.between(turnStartedAt, stoppedAt).toMillis());
        entity.settleRemainingOnFinish(mover.name(), Math.max(0, remaining - elapsedMs));
    }

    private GameState readState(GameSessionEntity entity) {
        try {
            return objectMapper.readValue(entity.getStateJson(), GameState.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize game data", exception);
        }
    }

    private GameResponse toResponse(GameSessionEntity entity, GameState state) {
        GameEngine engine = GameEngine.restore(state);
        Map<BoardPosition, List<BoardPosition>> legalMoves = new LinkedHashMap<>();
        engine.legalMoves().forEach((source, destinations) -> legalMoves.put(source, List.copyOf(destinations)));
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
            return new ClockView(0, 0, false, clock.instant(), null);
        }
        // A game that finish() just closed out is never still "running" - status is already the
        // terminal value by the time toResponse reads it here - so there is no turn deadline to show.
        return new ClockView(entity.getWhiteRemainingMs(), entity.getBlackRemainingMs(), false, clock.instant(), null);
    }

    private void publishAfterCommit(java.util.UUID gameId, GameResponse response) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    gameUpdates.broadcast(gameId, response);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Committed game {} finish could not be broadcast", gameId, exception);
                }
                try { events.publishEvent(new GameCommittedEvent(response)); }
                catch (RuntimeException exception) { LOGGER.warn("Committed finish {} could not notify bot scheduler", gameId, exception); }
            }
        });
    }
}
