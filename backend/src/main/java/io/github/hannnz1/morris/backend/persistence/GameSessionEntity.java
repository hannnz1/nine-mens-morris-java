package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {

    @Id
    private UUID id;

    @Version
    private long version;

    @Column(name = "white_player", nullable = false, length = 50)
    private String whitePlayer;

    @Column(name = "black_player", length = 50)
    private String blackPlayer;

    @Column(name = "white_token_hash", nullable = false, length = 64)
    private String whiteTokenHash;

    @Column(name = "black_token_hash", nullable = false, length = 64)
    private String blackTokenHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GameSessionEntity() {
    }

    public GameSessionEntity(UUID id, String whitePlayer, String blackPlayer,
                             String whiteTokenHash, String blackTokenHash,
                             String status, String stateJson, Instant createdAt) {
        this.id = id;
        this.whitePlayer = whitePlayer;
        this.blackPlayer = blackPlayer;
        this.whiteTokenHash = whiteTokenHash;
        this.blackTokenHash = blackTokenHash;
        this.status = status;
        this.stateJson = stateJson;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public String getWhitePlayer() {
        return whitePlayer;
    }

    public String getBlackPlayer() {
        return blackPlayer;
    }

    public String getWhiteTokenHash() {
        return whiteTokenHash;
    }

    public String getBlackTokenHash() {
        return blackTokenHash;
    }

    public String getStatus() {
        return status;
    }

    public String getStateJson() {
        return stateJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateState(String status, String stateJson, Instant updatedAt) {
        this.status = status;
        this.stateJson = stateJson;
        this.updatedAt = updatedAt;
    }

    public void joinBlackPlayer(String playerName, String tokenHash, Instant updatedAt) {
        this.blackPlayer = playerName;
        this.blackTokenHash = tokenHash;
        this.status = "IN_PROGRESS";
        this.updatedAt = updatedAt;
    }
}
