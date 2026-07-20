package io.github.hannnz1.morris.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.GameApiDtos.PlayerCredential;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordEntity;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordRepository;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.GameAction;
import io.github.hannnz1.morris.engine.GameEngine;
import io.github.hannnz1.morris.engine.GameState;
import io.github.hannnz1.morris.engine.Player;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GameSessionService {

    private final GameSessionRepository games;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final TokenService tokens;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public GameSessionService(GameSessionRepository games,
                              IdempotencyRecordRepository idempotencyRecords,
                              TokenService tokens,
                              ObjectMapper objectMapper,
                              SimpMessagingTemplate messagingTemplate) {
        this.games = games;
        this.idempotencyRecords = idempotencyRecords;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public CreateGameResponse create(CreateGameRequest request) {
        String whiteToken = tokens.generate();
        String blackToken = tokens.generate();
        Instant now = Instant.now();
        GameState state = GameEngine.newGame().state();
        GameSessionEntity entity = new GameSessionEntity(
                UUID.randomUUID(), request.whitePlayer().trim(), request.blackPlayer().trim(),
                tokens.hash(whiteToken), tokens.hash(blackToken), statusOf(state),
                writeJson(state), now);
        entity = games.saveAndFlush(entity);

        return new CreateGameResponse(
                toResponse(entity, state),
                new PlayerCredential(Player.WHITE, entity.getWhitePlayer(), whiteToken),
                new PlayerCredential(Player.BLACK, entity.getBlackPlayer(), blackToken));
    }

    @Transactional(readOnly = true)
    public GameResponse get(UUID id) {
        GameSessionEntity entity = findGame(id);
        return toResponse(entity, readState(entity));
    }

    @Transactional
    public GameResponse performAction(UUID id, String playerToken, String idempotencyKey,
                                      ActionRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validatePlayerToken(playerToken);
        String fingerprint = tokens.hash(playerToken + ":" + writeJson(request));

        var previous = idempotencyRecords.findByGameIdAndIdempotencyKey(id, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different request");
            }
            return readJson(previous.get().getResponseJson(), GameResponse.class);
        }

        GameSessionEntity entity = findGame(id);
        Player player = authenticate(entity, playerToken);
        GameState currentState = readState(entity);
        if (player != currentState.currentPlayer()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_TURN",
                    "Only the current player can perform this action");
        }
        if (request.expectedVersion() != entity.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "The supplied version is stale; reload the game before retrying");
        }

        GameState nextState = GameEngine.restore(currentState)
                .apply(new GameAction(request.type(), request.from(), request.to()));
        entity.updateState(statusOf(nextState), writeJson(nextState), Instant.now());
        entity = games.saveAndFlush(entity);
        GameResponse response = toResponse(entity, nextState);

        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(
                UUID.randomUUID(), id, idempotencyKey, fingerprint, writeJson(response), Instant.now()));
        publishAfterCommit(id, response);
        return response;
    }

    private GameSessionEntity findGame(UUID id) {
        return games.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "The game does not exist"));
    }

    private Player authenticate(GameSessionEntity entity, String playerToken) {
        if (tokens.matches(playerToken, entity.getWhiteTokenHash())) {
            return Player.WHITE;
        }
        if (tokens.matches(playerToken, entity.getBlackTokenHash())) {
            return Player.BLACK;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PLAYER_TOKEN",
                "The player token is invalid for this game");
    }

    private GameResponse toResponse(GameSessionEntity entity, GameState state) {
        GameEngine engine = GameEngine.restore(state);
        Map<BoardPosition, List<BoardPosition>> legalMoves = new LinkedHashMap<>();
        engine.legalMoves().forEach((source, destinations) ->
                legalMoves.put(source, List.copyOf(destinations)));
        return new GameResponse(entity.getId(), entity.getVersion(), entity.getWhitePlayer(),
                entity.getBlackPlayer(), entity.getStatus(), state.phase(), state,
                List.copyOf(engine.legalPlacements()), legalMoves, List.copyOf(engine.removablePieces()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private GameState readState(GameSessionEntity entity) {
        return readJson(entity.getStateJson(), GameState.class);
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

    private void validatePlayerToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PLAYER_TOKEN",
                    "The player token is invalid for this game");
        }
    }

    private void publishAfterCommit(UUID gameId, GameResponse response) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend("/topic/games/" + gameId, response);
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
