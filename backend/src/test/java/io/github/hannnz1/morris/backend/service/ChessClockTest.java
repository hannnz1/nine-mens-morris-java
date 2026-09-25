package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.ActionRequest;
import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordEntity;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.ActionType;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.GameEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessClockTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired private GameSessionService gameSessions;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private GameSessionRepository gameRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private TokenService tokens;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;

    private String whiteToken;
    private String blackToken;

    @Test
    void rejectsAnUnsupportedTimeControl() {
        PlayerEntity white = createPlayer("Han");
        assertThatThrownBy(() -> gameSessions.createForPlayer(white, "k1", "1+0"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
    }

    @Test
    void clockStartsWhenBlackJoinsNotWhenWhiteCreates() {
        PlayerEntity white = createPlayer("Han");
        var created = gameSessions.createForPlayer(white, "k1", "5+3");
        assertThat(created.game().clock().running()).isFalse();

        Instant joinInstant = clock.instant();
        PlayerEntity black = createPlayer("Zhu");
        var joined = gameSessions.joinByBearer(created.game().id(), black, "k2");
        assertThat(joined.game().clock().running()).isTrue();
        assertThat(joined.game().clock().whiteMs()).isEqualTo(5 * 60_000L);
        assertThat(joined.game().clock().blackMs()).isEqualTo(5 * 60_000L);
        assertThat(joined.game().clock().turnDeadlineAt()).isEqualTo(joinInstant.plusSeconds(30));
    }

    // Spec M2.3's first-move grace: "双方各自的第一步只有 30 秒（不消耗主时间）" - a side's own first
    // move (piecesToPlace == 9 going in) deducts no main time and adds no increment, regardless of
    // how much of the 30s grace it used.
    @Test
    void whitesFirstMoveLeavesFullBudgetUnchanged() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofSeconds(10)); // well within the 30s grace

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        assertThat(outcome.rejectedByTimeout()).isFalse();
        assertThat(outcome.game().clock().whiteMs()).isEqualTo(300_000L);
        assertThat(outcome.game().clock().blackMs()).isEqualTo(300_000L);
    }

    // Once both sides are past their own first move, ordinary deduction-plus-increment applies
    // again, exactly as before this fix - just proven on the second move instead of the first.
    @Test
    void aNonFirstMoveDeductsElapsedTimeAndAddsIncrementOnTurnHandoff() {
        var game = startedGame("5+3");

        var afterWhitesFirstMove = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlacksFirstMove = gameSessions.performAction(game.id(), blackToken, null, "act-2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhitesFirstMove.game().version()));

        ((MutableClock) clock).advance(Duration.ofSeconds(10));
        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-3",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, afterBlacksFirstMove.game().version()));

        // White used 10s of a 300s budget on its (non-first) move, then gains the 3s increment on
        // handing the turn over: 300_000 - 10_000 + 3_000 = 293_000. Black hasn't moved again since
        // its own first move, so its remaining time is still the untouched 300_000 baseline.
        assertThat(outcome.rejectedByTimeout()).isFalse();
        assertThat(outcome.game().clock().whiteMs()).isEqualTo(293_000L);
        assertThat(outcome.game().clock().blackMs()).isEqualTo(300_000L);
    }

    // Regression for the plan's own sample-code error: a NON-first move that arrives exactly when
    // the mover's main budget (not the 30s grace) reaches zero is rejected as a TIMEOUT loss, not
    // ABORTED - distinct from the first-move grace boundary covered below.
    @Test
    void aNonFirstMoveArrivingExactlyWhenTheMainBudgetReachesZeroIsRejectedAsATimeout() {
        var game = startedGame("5+3");

        var afterWhitesFirstMove = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlacksFirstMove = gameSessions.performAction(game.id(), blackToken, null, "act-2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhitesFirstMove.game().version()));

        // White's second-move deadline was set to its full 300s remaining budget (not a 30s grace,
        // since White already made its first move) - advance exactly that far.
        ((MutableClock) clock).advance(Duration.ofMillis(300_000));

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-3",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, afterBlacksFirstMove.game().version()));

        assertThat(outcome.rejectedByTimeout()).isTrue();
        assertThat(outcome.game().status()).isEqualTo("BLACK_WON");
    }

    // Regression for the ruling: a late FIRST move ends the game as ABORTED (winner null), not a
    // TIMEOUT loss, per spec M2.3's "超时扫描发现是第一步超时，就以 ABORTED 结束而不是 TIMEOUT".
    @Test
    void aLateFirstMoveFinishesAsAbortedNotTimeout() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofMillis(30_000)); // exactly White's 30s grace, not a ms less

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        assertThat(outcome.rejectedByTimeout()).isTrue();
        assertThat(outcome.game().status()).isEqualTo("ABORTED");
    }

    // Regression for the reported bug: after White's first move, Black's deadline must be a fresh
    // 30s grace (Black's own first move), not White's 300s main-budget remaining time.
    @Test
    void blacksFirstMoveGetsItsOwnThirtySecondGraceNotWhitesMainBudget() {
        var game = startedGame("5+3");

        var afterWhitesFirstMove = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        // If Black's deadline had incorrectly been set to White's ~300s remaining budget instead of
        // a 30s grace, this would NOT time out; because it's genuinely a 30s grace, it does.
        ((MutableClock) clock).advance(Duration.ofMillis(30_000));

        var outcome = gameSessions.performAction(game.id(), blackToken, null, "act-2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhitesFirstMove.game().version()));

        assertThat(outcome.rejectedByTimeout()).isTrue();
        assertThat(outcome.game().status()).isEqualTo("ABORTED");
    }

    // Regression for Important #3: once GameFinisher.finish has closed a game out (here via a
    // TIMEOUT), a fresh request (new idempotency key, so the replay path doesn't short-circuit)
    // must be rejected outright rather than re-entering the timeout branch and rewriting the result.
    @Test
    void aFinishedGameRejectsAFreshRequestWithoutRewritingTheResult() {
        var game = startedGame("5+3");
        var afterWhitesFirstMove = gameSessions.performAction(game.id(), whiteToken, null, "act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlacksFirstMove = gameSessions.performAction(game.id(), blackToken, null, "act-2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhitesFirstMove.game().version()));
        ((MutableClock) clock).advance(Duration.ofMillis(300_000));
        var timedOut = gameSessions.performAction(game.id(), whiteToken, null, "act-3",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, afterBlacksFirstMove.game().version()));
        assertThat(timedOut.game().status()).isEqualTo("BLACK_WON");

        assertThatThrownBy(() -> gameSessions.performAction(game.id(), whiteToken, null, "act-4-new-key",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A7, timedOut.game().version())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "GAME_NOT_ACTIVE");

        assertThat(gameSessions.get(game.id()).status()).isEqualTo("BLACK_WON");
    }

    // Regression for Critical #2: a Bearer game whose base_ms/increment_ms are NULL (a pre-M2
    // WAITING_FOR_PLAYER game at deploy - createForPlayer didn't call setTimeControl yet) must not
    // NPE when the second player joins; it falls back to the 5+3 default instead.
    @Test
    void joiningAGameWithNullBaseMsFallsBackToTheDefaultTimeControl() {
        PlayerEntity white = createPlayer("Han");
        Instant now = Instant.now();
        GameSessionEntity entity = new GameSessionEntity(UUID.randomUUID(), white.getNickname(), null,
                null, null, "WAITING_FOR_PLAYER", writeMinimalState(), now);
        entity.assignPlayers(white.getId(), null);
        gameRepository.saveAndFlush(entity);
        assertThat(entity.getBaseMs()).isNull();

        PlayerEntity black = createPlayer("Zhu");
        var joined = gameSessions.joinByBearer(entity.getId(), black, "join-null-basems");

        assertThat(joined.game().clock().running()).isTrue();
        assertThat(joined.game().clock().whiteMs()).isEqualTo(5 * 60_000L);
        assertThat(joined.game().clock().blackMs()).isEqualTo(5 * 60_000L);
    }

    // Regression for Important #4: idempotency_records has existed since V1, so a real deployment
    // can hold rows written before ActionOutcome wrapped GameResponse - a bare GameResponse JSON.
    // Jackson's default "ignore unknown properties" would otherwise deserialize that as
    // ActionOutcome(null, false), a 200 with an empty body, on replay.
    @Test
    void anOldShapeIdempotencyRecordReplaysTheStoredGameInsteadOfAnEmptyBody() throws Exception {
        var game = startedGame("5+3");
        String legacyKey = "act-pre-actionoutcome";
        var request = new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version());
        String fingerprint = tokens.hash(whiteToken + ":" + objectMapper.writeValueAsString(request));
        var storedGame = gameSessions.get(game.id()); // any valid GameResponse stands in for the V1-era payload
        idempotencyRecords.saveAndFlush(new IdempotencyRecordEntity(UUID.randomUUID(), game.id(), legacyKey,
                fingerprint, objectMapper.writeValueAsString(storedGame), Instant.now()));

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, legacyKey, request);

        assertThat(outcome.rejectedByTimeout()).isFalse();
        assertThat(outcome.game()).isNotNull();
        assertThat(outcome.game().id()).isEqualTo(storedGame.id());
        assertThat(outcome.game().version()).isEqualTo(storedGame.version());
    }

    // A clockless legacy (pre-M2, anonymous-join) IN_PROGRESS game has turnDeadlineAt == null and
    // must pass straight through performAction's clock-settlement block without error.
    @Test
    void aClocklessLegacyGamePassesThroughPerformActionWithoutError() {
        String legacyWhiteToken = tokens.generate();
        String legacyBlackToken = tokens.generate();
        Instant now = Instant.now();
        GameSessionEntity entity = new GameSessionEntity(UUID.randomUUID(), "Alice", "Bob",
                tokens.hash(legacyWhiteToken), tokens.hash(legacyBlackToken), "IN_PROGRESS",
                writeMinimalState(), now);
        gameRepository.saveAndFlush(entity);

        assertThatCode(() -> gameSessions.performAction(entity.getId(), null, legacyWhiteToken, "legacy-act-1",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, entity.getVersion())))
                .doesNotThrowAnyException();
    }

    private String writeMinimalState() {
        try {
            return objectMapper.writeValueAsString(GameEngine.newGame().state());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PlayerEntity createPlayer(String nickname) {
        Instant now = Instant.now();
        return playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), nickname,
                tokens.hash(tokens.generate()), "HUMAN", now, now));
    }

    private GameResponse startedGame(String timeControl) {
        String rawWhiteToken = tokens.generate();
        Instant now = Instant.now();
        PlayerEntity white = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Han",
                tokens.hash(rawWhiteToken), "HUMAN", now, now));
        this.whiteToken = rawWhiteToken;
        var created = gameSessions.createForPlayer(white, "create-" + UUID.randomUUID(), timeControl);

        String rawBlackToken = tokens.generate();
        PlayerEntity black = playerRepository.saveAndFlush(new PlayerEntity(UUID.randomUUID(), "Zhu",
                tokens.hash(rawBlackToken), "HUMAN", now, now));
        this.blackToken = rawBlackToken;
        var joined = gameSessions.joinByBearer(created.game().id(), black, "join-" + UUID.randomUUID());
        return joined.game();
    }
}
