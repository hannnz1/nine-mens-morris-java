package io.github.hannnz1.morris.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {
    Optional<IdempotencyRecordEntity> findByGameIdAndIdempotencyKey(UUID gameId, String idempotencyKey);
}

