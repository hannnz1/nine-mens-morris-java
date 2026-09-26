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
    @Query("select count(g) from GameSessionEntity g where g.status = 'IN_PROGRESS' and (g.whitePlayerId in :ids or g.blackPlayerId in :ids)")
    long countBotGames(@Param("ids") List<UUID> ids);
    // All writes to an existing game acquire this lock before checking its state.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from GameSessionEntity game where game.id = :id")
    Optional<GameSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<GameSessionEntity> findByRoomCodeAndStatusIn(String roomCode, java.util.List<String> statuses);

    // Unlocked candidate selection for TimeoutScanner's "Pattern B" scan, per spec M2.3: the scanner
    // takes no lock here - each candidate id is re-checked and locked individually afterward, one
    // transaction per id, so a concurrent action finishing the same game first is never overwritten.
    // Capped per spec section 2's "模式 B" (LIMIT 100): an unbounded candidate list would let one
    // scan tick fall arbitrarily far behind under a large backlog of expired games.
    @Query("""
        select game.id from GameSessionEntity game
        where game.status = 'IN_PROGRESS' and game.turnDeadlineAt <= :now
        """)
    List<UUID> findTimedOutCandidateIds(@Param("now") Instant now, org.springframework.data.domain.Limit limit);

    // NOT a derived-query method (countByWhitePlayerIdOrBlackPlayerIdAndStatusIn): Spring Data
    // derived query names bind "And" more tightly than "Or", so that method name actually means
    // "white = ? OR (black = ? AND status IN ?)", not the intended "(white = ? OR black = ?) AND
    // status IN ?" - any player who had ever created 5 games (regardless of status) would be
    // permanently blocked from creating a 6th. See M1 final whole-branch review C2.
    @Query("""
        select count(game) from GameSessionEntity game
        where (game.whitePlayerId = :playerId or game.blackPlayerId = :playerId)
          and game.status in :statuses
        """)
    long countActiveGamesForPlayer(@Param("playerId") UUID playerId, @Param("statuses") List<String> statuses);

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
