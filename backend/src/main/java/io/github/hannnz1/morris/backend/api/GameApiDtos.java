package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.engine.ActionType;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.GamePhase;
import io.github.hannnz1.morris.engine.GameState;
import io.github.hannnz1.morris.engine.Player;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameApiDtos {

    private GameApiDtos() {
    }

    public record CreateGameRequest(
            @NotBlank @Size(max = 50) String whitePlayer,
            @Size(max = 50) String blackPlayer
    ) {
    }

    public record JoinGameRequest(
            @NotBlank @Size(max = 50) String blackPlayer
    ) {
    }

    public record JoinGameResponse(
            GameResponse game,
            PlayerCredential credential
    ) {
    }

    public record PlayerCredential(Player side, String playerName, String token) {
    }

    public record CreateGameResponse(
            GameResponse game,
            PlayerCredential whiteCredential,
            PlayerCredential blackCredential
    ) {
    }

    public record ActionRequest(
            @NotNull ActionType type,
            BoardPosition from,
            BoardPosition to,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record GameResponse(
            UUID id,
            long version,
            String whitePlayer,
            String blackPlayer,
            String status,
            GamePhase phase,
            GameState state,
            List<BoardPosition> legalPlacements,
            Map<BoardPosition, List<BoardPosition>> legalMoves,
            List<BoardPosition> removablePieces,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ApiError(
            Instant timestamp,
            int status,
            String code,
            String message,
            String path,
            List<FieldError> fieldErrors
    ) {
    }

    public record FieldError(String field, String message) {
    }
}
