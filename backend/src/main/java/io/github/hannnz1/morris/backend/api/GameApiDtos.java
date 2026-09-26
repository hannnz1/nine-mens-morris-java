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
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameApiDtos {

    private GameApiDtos() {
    }

    public record CreateGameRequest(
            @NotBlank @Size(max = 50) String whitePlayer
    ) {
    }

    public record JoinGameRequest(
            @NotBlank @Size(max = 50) String blackPlayer,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String joinToken
    ) {
    }

    public record JoinGameResponse(
            GameResponse game,
            PlayerCredential credential
    ) {
    }

    public record PlayerCredential(Player side, String playerName, String token) {
    }

    public record CreateGameRequestForPlayer(String timeControl) {
    }

    public record CreateGameResponse(
            GameResponse game,
            PlayerCredential whiteCredential,
            String roomCode
    ) {
    }

    public record RoomLookupResponse(UUID gameId, String status, String whiteNickname) {
    }

    public record ActionRequest(
            @NotNull ActionType type,
            BoardPosition from,
            BoardPosition to,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record DrawActionRequest(@NotBlank String action) {
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
            Instant updatedAt,
            ClockView clock,
            String drawOfferedBy,
            ResultView result,
            String rematchOfferedBy,
            UUID rematchGameId,
            // Player identity ids (null for a legacy anonymous seat / an empty seat). Clients work
            // out their own side by comparing these with their own playerId - never by nickname,
            // since two identities may share a nickname. Ids grant nothing: auth is by token hash.
            UUID whitePlayerId,
            UUID blackPlayerId,
            // "3+2" / "5+3" / "10+5", or null for a clockless (legacy anonymous) game. Known before
            // anyone joins, unlike the clock, which stays all zeros until Black is seated.
            String timeControl
    ) {
    }

    public record ClockView(long whiteMs, long blackMs, boolean running, Instant serverNow, Instant turnDeadlineAt) {
    }

    public record ResultView(String winner, String reason) {
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
