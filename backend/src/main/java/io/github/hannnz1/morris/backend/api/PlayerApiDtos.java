package io.github.hannnz1.morris.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlayerApiDtos {

    private PlayerApiDtos() {
    }

    public record CreatePlayerRequest(
            @NotBlank String nickname,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String clientToken
    ) {
    }

    public record RenamePlayerRequest(@NotBlank String nickname) {
    }

    public record PlayerResponse(UUID playerId, String nickname, Instant createdAt) {
    }

    public record GameSummary(UUID gameId, String status, String opponentNickname, Instant updatedAt) {
    }

    public record GameListResponse(List<GameSummary> games, Instant nextBefore) {
    }
}
