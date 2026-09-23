package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GameSessionRepository extends JpaRepository<GameSessionEntity, UUID> {
    // All writes to an existing game acquire this lock before checking its state.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from GameSessionEntity game where game.id = :id")
    Optional<GameSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<GameSessionEntity> findByRoomCodeAndStatusIn(String roomCode, java.util.List<String> statuses);

    long countByWhitePlayerIdOrBlackPlayerIdAndStatusIn(UUID whitePlayerId, UUID blackPlayerId, java.util.List<String> statuses);
}
