package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionRepository extends JpaRepository<GameSessionEntity, UUID> {
    // All writes to an existing game acquire this lock before checking its state.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from GameSessionEntity game where game.id = :id")
    Optional<GameSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<GameSessionEntity> findByRoomCodeAndStatusIn(String roomCode, java.util.List<String> statuses);

    long countByWhitePlayerIdOrBlackPlayerIdAndStatusIn(UUID whitePlayerId, UUID blackPlayerId, java.util.List<String> statuses);

    // Two separate queries rather than a single "(:before is null or ...)" JPQL predicate: binding
    // a null Instant into that OR clause makes the Postgres extended-query protocol unable to infer
    // the parameter's type ("could not determine data type of parameter $n") because, when the null
    // branch is taken, that bind position is only ever compared against IS NULL and never against a
    // typed column. Splitting avoids ever binding a null Instant.
    @Query("""
        select game from GameSessionEntity game
        where (game.whitePlayerId = :playerId or game.blackPlayerId = :playerId)
          and game.status in :statuses
        order by game.updatedAt desc
        """)
    List<GameSessionEntity> findForPlayer(@Param("playerId") UUID playerId,
                                           @Param("statuses") List<String> statuses,
                                           Pageable pageable);

    @Query("""
        select game from GameSessionEntity game
        where (game.whitePlayerId = :playerId or game.blackPlayerId = :playerId)
          and game.status in :statuses
          and game.updatedAt < :before
        order by game.updatedAt desc
        """)
    List<GameSessionEntity> findForPlayerBefore(@Param("playerId") UUID playerId,
                                                 @Param("statuses") List<String> statuses,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}
