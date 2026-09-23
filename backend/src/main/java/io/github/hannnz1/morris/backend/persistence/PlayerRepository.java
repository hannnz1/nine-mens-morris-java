package io.github.hannnz1.morris.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerEntity, UUID> {
    Optional<PlayerEntity> findByTokenHash(String tokenHash);
}
