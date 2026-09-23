package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "players", uniqueConstraints = {
        @UniqueConstraint(name = "uk_players_token_hash", columnNames = "token_hash")
})
public class PlayerEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 16)
    private String nickname;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String tokenHash;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected PlayerEntity() {
    }

    public PlayerEntity(UUID id, String nickname, String tokenHash, String kind, Instant createdAt, Instant lastSeenAt) {
        this.id = id;
        this.nickname = nickname;
        this.tokenHash = tokenHash;
        this.kind = kind;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void renameTo(String nickname) {
        this.nickname = nickname;
    }

    public void touchLastSeen(Instant now) {
        this.lastSeenAt = now;
    }
}
