package io.github.hannnz1.morris.engine.ai;

import io.github.hannnz1.morris.engine.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static io.github.hannnz1.morris.engine.BoardPosition.*;
import static org.assertj.core.api.Assertions.*;

class MorrisAiTest {
    final AlphaBetaMorrisAi ai = new AlphaBetaMorrisAi();

    static GameState position(String white, String black, int reserve, boolean removal) {
        var board = new EnumMap<BoardPosition, Piece>(BoardPosition.class);
        for (var p : BoardPosition.values()) board.put(p, Piece.EMPTY);
        for (var p : white.split(" ")) if (!p.isEmpty()) board.put(BoardPosition.valueOf(p), Piece.WHITE);
        for (var p : black.split(" ")) if (!p.isEmpty()) board.put(BoardPosition.valueOf(p), Piece.BLACK);
        return new GameState(board, Player.WHITE, reserve, reserve, removal, null, 20, Map.of(), 0, null);
    }

    @Test void millRemovalKeepsPerspectiveAndDepth() {
        var s = position("A1 D1 B2", "C3 D3 E5", 0, false);
        var decision = ai.chooseDetailed(s, Difficulty.MEDIUM, 42, Duration.ofMillis(800));
        assertThat(decision.fallbackUsed()).isFalse();
        var after = GameEngine.restore(s).apply(decision.action());
        assertThat(after.removalPending()).isTrue();
        var capture = ai.chooseAction(after, Difficulty.MEDIUM, 43, Duration.ofMillis(800));
        assertThat(GameEngine.restore(after).apply(capture).winner()).isEqualTo(Player.WHITE);
        assertThat(s.board().get(B2)).isEqualTo(Piece.WHITE);
    }
    @Test void blocksAnImmediateMillInPlacingPhase() {
        var s = position("A1 B2", "C3 D3", 7, false);
        assertThat(ai.chooseAction(s, Difficulty.MEDIUM, 42, Duration.ofMillis(800)))
                .isEqualTo(GameAction.place(E3));
    }
    @Test void onlyLegalCapturesWhenAllOpponentPiecesAreInMills() {
        var s = position("A1 D1 G1", "C3 D3 E3", 4, true);
        assertThat(LegalActions.of(s)).containsExactlyInAnyOrder(
                GameAction.remove(C3), GameAction.remove(D3), GameAction.remove(E3));
        for (var d : Difficulty.values()) {
            var choice = ai.chooseDetailed(s, d, 7, Duration.ofMillis(20));
            assertThat(choice.action().type()).isEqualTo(ActionType.REMOVE);
            assertThatCode(() -> GameEngine.restore(s).apply(choice.action())).doesNotThrowAnyException();
            assertThat(choice.fallbackUsed()).isFalse();
        }
    }
    @Test void piecesOutsideMillsMustBeCapturedFirst() {
        var s = position("A1 D1 G1", "C3 D3 E3 B2", 3, true);
        assertThat(LegalActions.of(s)).containsExactly(GameAction.remove(B2));
    }
    @Test void flyingWithThreePieces() {
        var s = position("A1 D1 B2", "C3 D3 E3 F6", 0, false);
        assertThat(LegalActions.of(s)).contains(GameAction.move(B2, G7));
        for (var action : LegalActions.of(s)) assertThatCode(() -> GameEngine.restore(s).apply(action)).doesNotThrowAnyException();
    }
    @Test void sameSeedSameFixedDepthAction() {
        var s = GameEngine.newGame().state();
        for (var d : List.of(Difficulty.EASY, Difficulty.MEDIUM)) {
            var expected = ai.chooseAction(s, d, 17, Duration.ofMillis(800));
            for (int i = 0; i < 10; i++) assertThat(ai.chooseAction(s, d, 17, Duration.ofMillis(800))).isEqualTo(expected);
        }
        assertThat(s.piecesOnBoard(Player.WHITE)).isZero();
    }
    @Test void completedGameHasNoAction() {
        var s = GameEngine.newGame().state();
        var done = new GameState(s.board(), Player.WHITE, 0, 0, false, Player.WHITE, 2, Map.of(), 0, null);
        assertThat(LegalActions.of(done)).isEmpty();
        assertThatThrownBy(() -> ai.chooseAction(done, Difficulty.HARD, 1, Duration.ofMillis(10))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void tinyHardBudgetStillReturnsLegalActionWithoutFallback() {
        var s = GameEngine.newGame().state();
        var decision = ai.chooseDetailed(s, Difficulty.HARD, 1, Duration.ofNanos(1));
        assertThat(decision.fallbackUsed()).isFalse();
        assertThatCode(() -> GameEngine.restore(s).apply(decision.action())).doesNotThrowAnyException();
    }
}
