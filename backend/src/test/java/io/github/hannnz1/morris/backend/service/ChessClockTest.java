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
import io.github.hannnz1.morris.engine.GameState;
import io.github.hannnz1.morris.engine.Piece;
import io.github.hannnz1.morris.engine.Player;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    // The invite card shows the time control before anyone joins, when the clock itself is still
    // all zeros - so the response carries the label separately.
    @Test
    void aWaitingGameAlreadyReportsItsTimeControlLabel() {
        PlayerEntity white = createPlayer("Han");
        var created = gameSessions.createForPlayer(white, "tc-label", "3+2");
        assertThat(created.game().timeControl()).isEqualTo("3+2");
        assertThat(gameSessions.get(created.game().id()).timeControl()).isEqualTo("3+2");
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

    // Final-review I2: GameFinisher stops the clock at the right value. Before the fix the final
    // snapshot still showed the loser's whole remaining budget as of the start of its last turn.
    @Test
    void aTimeoutFinishShowsTheLosersClockAtZero() {
        var game = playBothFirstMoves(startedGame("5+3"));
        ((MutableClock) clock).advance(Duration.ofMillis(300_001)); // 1ms past White's main budget

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-late",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A4, game.version()));

        assertThat(outcome.rejectedByTimeout()).isTrue();
        assertThat(outcome.game().status()).isEqualTo("BLACK_WON");
        assertThat(outcome.game().result().reason()).isEqualTo("TIMEOUT");
        assertThat(outcome.game().clock().running()).isFalse();
        assertThat(outcome.game().clock().whiteMs()).isZero();
        assertThat(outcome.game().clock().blackMs()).isEqualTo(300_000L);
        GameSessionEntity stored = gameRepository.findById(game.id()).orElseThrow();
        assertThat(stored.getWhiteRemainingMs()).isZero();
        assertThat(stored.getBlackRemainingMs()).isEqualTo(300_000L);
    }

    @Test
    void aResignationMidTurnDeductsTheElapsedTurnTimeFromTheSideToMove() {
        var game = playBothFirstMoves(startedGame("5+3"));
        ((MutableClock) clock).advance(Duration.ofSeconds(10)); // White (to move) thinks for 10s

        GameResponse resigned = gameSessions.resign(game.id(), whiteToken, "resign-1");

        assertThat(resigned.status()).isEqualTo("BLACK_WON");
        assertThat(resigned.clock().whiteMs()).isEqualTo(290_000L); // no increment on a finish
        assertThat(resigned.clock().blackMs()).isEqualTo(300_000L);
        assertThat(gameRepository.findById(game.id()).orElseThrow().getWhiteRemainingMs()).isEqualTo(290_000L);
    }

    @Test
    void theNonMovingSideResigningStillChargesTheSideToMove() {
        var game = playBothFirstMoves(startedGame("5+3"));
        ((MutableClock) clock).advance(Duration.ofSeconds(7)); // White is to move; Black resigns

        GameResponse resigned = gameSessions.resign(game.id(), blackToken, "resign-black");

        assertThat(resigned.status()).isEqualTo("WHITE_WON");
        assertThat(resigned.clock().whiteMs()).isEqualTo(293_000L);
        assertThat(resigned.clock().blackMs()).isEqualTo(300_000L);
    }

    @Test
    void aFirstMoveAbortLeavesTheMoversMainTimeUntouched() {
        var game = startedGame("5+3");
        ((MutableClock) clock).advance(Duration.ofSeconds(31));

        var outcome = gameSessions.performAction(game.id(), whiteToken, null, "act-aborted",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));

        assertThat(outcome.game().status()).isEqualTo("ABORTED");
        assertThat(outcome.game().clock().whiteMs()).isEqualTo(300_000L);
        assertThat(outcome.game().clock().blackMs()).isEqualTo(300_000L);
    }

    @Test
    void anAgreedDrawClearsTheDrawOfferAndSettlesTheSideToMove() {
        var game = playBothFirstMoves(startedGame("5+3"));
        ((MutableClock) clock).advance(Duration.ofSeconds(4));

        GameResponse offered = gameSessions.offerDraw(game.id(), whiteToken, "draw-offer", "OFFER");
        assertThat(offered.drawOfferedBy()).isEqualTo("WHITE");
        GameResponse accepted = gameSessions.offerDraw(game.id(), blackToken, "draw-accept", "ACCEPT");

        assertThat(accepted.status()).isEqualTo("DRAWN");
        assertThat(accepted.result().reason()).isEqualTo("DRAW_AGREED");
        assertThat(accepted.drawOfferedBy()).isNull();
        assertThat(gameRepository.findById(game.id()).orElseThrow().getDrawOfferedBy()).isNull();
        assertThat(accepted.clock().whiteMs()).isEqualTo(296_000L);
        assertThat(accepted.clock().blackMs()).isEqualTo(300_000L);
    }

    // Spec M2.3 / M2.9: "成三后移除对方棋子 - 计时不中断，也不加秒。只有回合交给对方时才加秒". Built
    // with real moves only; the mill placement (White G1) is White's 3rd placement, not a first
    // move for either side, so ordinary main-time accounting applies throughout.
    @Test
    void aMillPlacementAddsNoIncrementAndTheFollowingRemoveAddsItOnHandoff() {
        GameResponse game = playBothFirstMoves(startedGame("5+3")); // White A1, Black D2 (both first moves)

        ((MutableClock) clock).advance(Duration.ofSeconds(5));
        game = gameSessions.performAction(game.id(), whiteToken, null, "mill-w2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D1, game.version())).game();
        assertThat(game.clock().whiteMs()).isEqualTo(298_000L); // 300_000 - 5_000 + 3_000

        ((MutableClock) clock).advance(Duration.ofSeconds(4));
        game = gameSessions.performAction(game.id(), blackToken, null, "mill-b2",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.B2, game.version())).game();
        assertThat(game.clock().blackMs()).isEqualTo(299_000L); // 300_000 - 4_000 + 3_000

        // White G1 completes the A1-D1-G1 mill: the turn does not pass (a removal is pending).
        ((MutableClock) clock).advance(Duration.ofSeconds(7));
        Instant millAt = clock.instant();
        GameResponse afterMill = gameSessions.performAction(game.id(), whiteToken, null, "mill-w3",
                new ActionRequest(ActionType.PLACE, null, BoardPosition.G1, game.version())).game();
        assertThat(afterMill.phase().name()).isEqualTo("REMOVE");
        assertThat(afterMill.state().currentPlayer().name()).isEqualTo("WHITE");
        assertThat(afterMill.clock().whiteMs()).isEqualTo(291_000L); // 298_000 - 7_000, NO increment
        assertThat(afterMill.clock().blackMs()).isEqualTo(299_000L);
        // The deadline is White's OWN remaining time from now, not Black's.
        assertThat(afterMill.clock().turnDeadlineAt()).isEqualTo(millAt.plusMillis(291_000L));
        GameSessionEntity midRemoval = gameRepository.findById(game.id()).orElseThrow();
        assertThat(midRemoval.getTurnStartedAt()).isEqualTo(millAt);

        // The REMOVE deducts only the time since the mill placement, then hands off (+increment).
        ((MutableClock) clock).advance(Duration.ofSeconds(2));
        Instant removeAt = clock.instant();
        GameResponse afterRemove = gameSessions.performAction(game.id(), whiteToken, null, "mill-w4",
                new ActionRequest(ActionType.REMOVE, null, BoardPosition.B2, afterMill.version())).game();
        assertThat(afterRemove.state().currentPlayer().name()).isEqualTo("BLACK");
        assertThat(afterRemove.clock().whiteMs()).isEqualTo(292_000L); // 291_000 - 2_000 + 3_000
        assertThat(afterRemove.clock().blackMs()).isEqualTo(299_000L);
        assertThat(afterRemove.clock().turnDeadlineAt()).isEqualTo(removeAt.plusMillis(299_000L));
    }

    // End-to-end settlement when the engine itself ends the game: a winning REMOVE charges the
    // mover's elapsed turn time, adds no increment (the turn never hands off), leaves the loser's
    // clock untouched, and stops the clock.
    @Test
    void aGameEndingMoveSettlesTheMoversClockWithoutIncrementAndStopsTheClock() {
        GameResponse game = playBothFirstMoves(startedGame("5+3")); // White to move, 300_000 each

        // Jump to an endgame: White has just milled (A1-D1-G1) and must remove; Black is down to
        // three loose pieces, so the removal leaves Black with two and ends the game.
        EnumMap<BoardPosition, Piece> board = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) board.put(position, Piece.EMPTY);
        for (BoardPosition position : List.of(BoardPosition.A1, BoardPosition.D1, BoardPosition.G1, BoardPosition.A4))
            board.put(position, Piece.WHITE);
        for (BoardPosition position : List.of(BoardPosition.B2, BoardPosition.F4, BoardPosition.D6))
            board.put(position, Piece.BLACK);
        GameState endgame = new GameState(board, Player.WHITE, 0, 0, true, null, 40, Map.of(), 0, null);
        GameSessionEntity entity = gameRepository.findById(game.id()).orElseThrow();
        try {
            entity.updateState("IN_PROGRESS", objectMapper.writeValueAsString(endgame), clock.instant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        long version = gameRepository.saveAndFlush(entity).getVersion();

        ((MutableClock) clock).advance(Duration.ofSeconds(6));
        GameResponse finished = gameSessions.performAction(game.id(), whiteToken, null, "winning-remove",
                new ActionRequest(ActionType.REMOVE, null, BoardPosition.B2, version)).game();

        assertThat(finished.status()).isEqualTo("WHITE_WON");
        assertThat(finished.result().reason()).isEqualTo("NO_PIECES");
        assertThat(finished.clock().whiteMs()).isEqualTo(294_000L); // 300_000 - 6_000, no increment
        assertThat(finished.clock().blackMs()).isEqualTo(300_000L);
        assertThat(finished.clock().running()).isFalse();
        assertThat(finished.clock().turnDeadlineAt()).isNull();
    }

    private GameResponse playBothFirstMoves(GameResponse game) {
        var afterWhite = gameSessions.performAction(game.id(), whiteToken, null, "first-w-" + UUID.randomUUID(),
                new ActionRequest(ActionType.PLACE, null, BoardPosition.A1, game.version()));
        var afterBlack = gameSessions.performAction(game.id(), blackToken, null, "first-b-" + UUID.randomUUID(),
                new ActionRequest(ActionType.PLACE, null, BoardPosition.D2, afterWhite.game().version()));
        return afterBlack.game();
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
