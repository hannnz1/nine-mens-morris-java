package io.github.hannnz1.morris.backend.persistence;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerRepositoryTest extends PostgresIntegrationTest {

    @Autowired
    private PlayerRepository players;

    @Test
    void findsAPlayerByTokenHash() {
        Instant now = Instant.now();
        PlayerEntity saved = players.saveAndFlush(
                new PlayerEntity(UUID.randomUUID(), "Han", "a".repeat(64), "HUMAN", now, now));

        assertThat(players.findByTokenHash("a".repeat(64)))
                .isPresent()
                .get().extracting(PlayerEntity::getNickname).isEqualTo("Han");
        assertThat(players.findByTokenHash("b".repeat(64))).isEmpty();
    }

    @Test
    void rejectsADuplicateTokenHash() {
        Instant now = Instant.now();
        players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han", "c".repeat(64), "HUMAN", now, now));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> players.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Someone", "c".repeat(64), "HUMAN", now, now)));
    }
}
