package io.github.hannnz1.morris.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlayerIdempotencyRecordRepository
        extends JpaRepository<PlayerIdempotencyRecordEntity, PlayerIdempotencyRecordEntity.Key> {
    Optional<PlayerIdempotencyRecordEntity> findByIdPlayerIdAndIdIdempotencyKey(UUID playerId, String idempotencyKey);
}
