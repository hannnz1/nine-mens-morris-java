package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = {
        @UniqueConstraint(name = "uk_idempotency_game_key", columnNames = {"game_id", "idempotency_key"})
})
public class IdempotencyRecordEntity {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(UUID id, UUID gameId, String idempotencyKey,
                                   String requestFingerprint, String responseJson, Instant createdAt) {
        this.id = id;
        this.gameId = gameId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getResponseJson() {
        return responseJson;
    }
}
