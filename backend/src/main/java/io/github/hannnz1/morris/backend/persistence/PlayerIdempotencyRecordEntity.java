package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "player_idempotency_records")
public class PlayerIdempotencyRecordEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "request_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requestFingerprint;

    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlayerIdempotencyRecordEntity() {
    }

    public PlayerIdempotencyRecordEntity(UUID playerId, String idempotencyKey, String requestFingerprint,
                                          String responseJson, Instant createdAt) {
        this.id = new Key(playerId, idempotencyKey);
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

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "player_id")
        private UUID playerId;
        @Column(name = "idempotency_key", length = 100)
        private String idempotencyKey;

        protected Key() {
        }

        public Key(UUID playerId, String idempotencyKey) {
            this.playerId = playerId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(playerId, key.playerId) && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, idempotencyKey);
        }
    }
}
