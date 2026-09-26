package io.github.hannnz1.morris.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerEntity, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PlayerEntity p where p.id = :id")
    Optional<PlayerEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<PlayerEntity> findByTokenHash(String tokenHash);
}
