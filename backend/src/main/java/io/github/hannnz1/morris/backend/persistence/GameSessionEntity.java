package io.github.hannnz1.morris.backend.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {

    @Id
    private UUID id;

    // Writes acquire a pessimistic row lock; retain the revision for API/WebSocket ordering
    // and as a defensive check for any future writer that bypasses that convention.
    @Version
    private long version;

    @Column(name = "white_player", nullable = false, length = 50)
    private String whitePlayer;

    @Column(name = "black_player", length = 50)
    private String blackPlayer;

    // V2 migration dropped the NOT NULL constraint on both columns: a Bearer/identity-created game
    // (GameSessionService.createForPlayer) legitimately stores null here (see M1 final review M1).
    @Column(name = "white_token_hash", length = 64)
    private String whiteTokenHash;

    @Column(name = "black_token_hash", length = 64)
    private String blackTokenHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "white_player_id")
    private UUID whitePlayerId;

    @Column(name = "black_player_id")
    private UUID blackPlayerId;

    @Column(name = "room_code", length = 6, columnDefinition = "char(6)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String roomCode;

    @Column(name = "base_ms")
    private Integer baseMs;

    @Column(name = "increment_ms")
    private Integer incrementMs;

    @Column(name = "white_remaining_ms")
    private Long whiteRemainingMs;

    @Column(name = "black_remaining_ms")
    private Long blackRemainingMs;

    @Column(name = "turn_started_at")
    private Instant turnStartedAt;

    @Column(name = "turn_deadline_at")
    private Instant turnDeadlineAt;

    @Column(name = "result_winner", length = 5)
    private String resultWinner;

    @Column(name = "result_reason", length = 20)
    private String resultReason;

    @Column(name = "draw_offered_by", length = 5)
    private String drawOfferedBy;

    @Column(name = "white_draw_offers", nullable = false)
    private short whiteDrawOffers;

    @Column(name = "black_draw_offers", nullable = false)
    private short blackDrawOffers;

    @Column(name = "rematch_offered_by", length = 5)
    private String rematchOfferedBy;

    @Column(name = "rematch_game_id")
    private UUID rematchGameId;

    @Column(name = "finished_at")
    private Instant finishedAt;

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

    public UUID getWhitePlayerId() {
        return whitePlayerId;
    }

    public UUID getBlackPlayerId() {
        return blackPlayerId;
    }

    public void assignPlayers(UUID whitePlayerId, UUID blackPlayerId) {
        this.whitePlayerId = whitePlayerId;
        this.blackPlayerId = blackPlayerId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void assignRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public Integer getBaseMs() {
        return baseMs;
    }

    public Integer getIncrementMs() {
        return incrementMs;
    }

    public Long getWhiteRemainingMs() {
        return whiteRemainingMs;
    }

    public Long getBlackRemainingMs() {
        return blackRemainingMs;
    }

    public Instant getTurnStartedAt() {
        return turnStartedAt;
    }

    public Instant getTurnDeadlineAt() {
        return turnDeadlineAt;
    }

    public String getResultWinner() {
        return resultWinner;
    }

    public String getResultReason() {
        return resultReason;
    }

    public String getDrawOfferedBy() {
        return drawOfferedBy;
    }

    public short getWhiteDrawOffers() {
        return whiteDrawOffers;
    }

    public short getBlackDrawOffers() {
        return blackDrawOffers;
    }

    public String getRematchOfferedBy() {
        return rematchOfferedBy;
    }

    public UUID getRematchGameId() {
        return rematchGameId;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void finish(String status, String resultWinner, String resultReason, Instant finishedAt) {
        this.status = status;
        this.resultWinner = resultWinner;
        this.resultReason = resultReason;
        this.finishedAt = finishedAt;
        this.updatedAt = finishedAt;
    }

    public void startClock(int baseMs, int incrementMs, Instant now) {
        this.baseMs = baseMs;
        this.incrementMs = incrementMs;
        this.whiteRemainingMs = (long) baseMs;
        this.blackRemainingMs = (long) baseMs;
        this.turnStartedAt = now;
        this.turnDeadlineAt = now.plusSeconds(30); // first-move grace, spec M2.3
    }

    // Stores the chosen time control at game creation without starting the countdown - the clock
    // only starts (via startClock) once the second player joins, per spec M2.3.
    public void setTimeControl(int baseMs, int incrementMs) {
        this.baseMs = baseMs;
        this.incrementMs = incrementMs;
    }

    // Settles the clock after a legally-applied action: records both players' remaining time,
    // resets turnStartedAt to now, and sets the next deadline from nextDeadlineBudgetMs (the
    // opponent's remaining time on a turn handoff, or the mover's own remaining time if the turn
    // has not yet passed, e.g. mid-removal), matching spec M2.3 exactly.
    public void settleClock(long whiteRemainingMs, long blackRemainingMs, Instant turnStartedAt,
                            long nextDeadlineBudgetMs) {
        this.whiteRemainingMs = whiteRemainingMs;
        this.blackRemainingMs = blackRemainingMs;
        this.turnStartedAt = turnStartedAt;
        this.turnDeadlineAt = turnStartedAt.plusMillis(nextDeadlineBudgetMs);
    }
}
