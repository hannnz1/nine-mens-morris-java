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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single entry point for ending an ACTIVE game: mill/no-moves win, any draw, timeout, or
 * resignation all call this - never {@code entity.updateState}/{@code entity.finish} directly.
 * Caller must hold the row lock (via {@code findByIdForUpdate}) and be inside the write transaction
 * that will commit the result; this method does not lock or commit anything itself.
 */
@Component
public class GameFinisher {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GameFinisher.class);

    private final GameSessionRepository games;
    private final GameWebSocketHandler gameUpdates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GameFinisher(GameSessionRepository games, GameWebSocketHandler gameUpdates,
                         ObjectMapper objectMapper, Clock clock) {
        this.games = games;
        this.gameUpdates = gameUpdates;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public GameResponse finish(GameSessionEntity entity, Player winner, String reason) {
        String status = switch (reason) {
            case "DRAW_REPETITION", "DRAW_NO_CAPTURE", "DRAW_AGREED" -> "DRAWN";
            case "ABORTED" -> "ABORTED";
            default -> winner.name() + "_WON";
        };
        entity.finish(status, winner == null ? null : winner.name(), reason, clock.instant());
        entity = games.saveAndFlush(entity);

        GameState state = readState(entity);
        GameResponse response = toResponse(entity, state);
        publishAfterCommit(entity.getId(), response);
        return response;
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
                entity.getDrawOfferedBy(), resultView(entity));
    }

    private ResultView resultView(GameSessionEntity entity) {
        if (entity.getResultReason() == null) {
            return null;
        }
        return new ResultView(entity.getResultWinner(), entity.getResultReason());
    }

    private ClockView clockView(GameSessionEntity entity) {
        if (entity.getTurnDeadlineAt() == null) {
            return new ClockView(0, 0, false, clock.instant());
        }
        // A game that finish() just closed out is never still "running" - status is already the
        // terminal value by the time toResponse reads it here.
        return new ClockView(entity.getWhiteRemainingMs(), entity.getBlackRemainingMs(), false, clock.instant());
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
            }
        });
    }
}
