package io.github.hannnz1.morris.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawDetectionTest {

    @Test
    void threefoldRepetitionInTheMovingPhaseIsADraw() {
        // Two white pieces (A1<->A4 shuttle + G7 stationary) and two black pieces (D7<->D6 shuttle +
        // G4 stationary) - all four adjacency pairs used here are confirmed in BoardTopology.java.
        // Neither side ever holds all three points of any mill (verified by inspection of
        // BoardTopology.mills()), so no capture ever interrupts the repetition.
        GameEngine engine = minimalMovingPhasePosition();
        BoardPosition[] whiteShuttle = {BoardPosition.A1, BoardPosition.A4};
        BoardPosition[] blackShuttle = {BoardPosition.D7, BoardPosition.D6};
        int whiteMoves = 0;
        int blackMoves = 0;
        GameState state = engine.state();

        int guard = 0;
        while (state.drawReason() == null) {
            guard++;
            assertThat(guard).as("fixture should draw well within 20 plies if it's wired correctly").isLessThan(20);
            boolean whiteTurn = state.currentPlayer() == Player.WHITE;
            BoardPosition[] shuttle = whiteTurn ? whiteShuttle : blackShuttle;
            int index = whiteTurn ? whiteMoves : blackMoves;
            BoardPosition from = shuttle[index % 2];
            BoardPosition to = shuttle[(index + 1) % 2];
            state = engine.apply(new GameAction(ActionType.MOVE, from, to));
            engine = GameEngine.restore(state);
            if (whiteTurn) whiteMoves++; else blackMoves++;
        }

        assertThat(state.drawReason()).isEqualTo("DRAW_REPETITION");
        assertThat(state.winner()).isNull();
        assertThat(state.phase()).isEqualTo(GamePhase.GAME_OVER);
        GameEngine finishedEngine = engine;
        assertThrows(GameRuleException.class, () -> finishedEngine.apply(new GameAction(ActionType.MOVE, BoardPosition.A1, BoardPosition.A4)));
    }

    @Test
    void fiftyPliesInTheMovingPhaseWithNoCaptureIsADraw() {
        // Both sides fly (3 pieces each - any empty square is a legal destination, so this needs no
        // board-adjacency reasoning). Two pieces per side stay put as anchors; the third cycles
        // through a fixed, side-exclusive pool of squares (white: 9 squares, black: 8 - coprime
        // sizes). The exact (whitePos, blackPos, sideToMove) triple can only recur once both
        // individual cycles AND turn parity realign, which is lcm(9,8)*2 = 144 plies out - so no
        // repetition is possible before this test's 50-ply cutoff, and DRAW_NO_CAPTURE (not
        // DRAW_REPETITION) is guaranteed to be what actually fires.
        List<BoardPosition> all = new ArrayList<>(List.of(BoardPosition.values()));
        BoardPosition whiteAnchor1 = all.remove(0);
        BoardPosition whiteAnchor2 = all.remove(0);
        BoardPosition blackAnchor1 = all.remove(0);
        BoardPosition blackAnchor2 = all.remove(0);
        List<BoardPosition> whitePool = new ArrayList<>(all.subList(0, 9));
        List<BoardPosition> blackPool = new ArrayList<>(all.subList(9, 17));

        EnumMap<BoardPosition, Piece> board = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) board.put(position, Piece.EMPTY);
        board.put(whiteAnchor1, Piece.WHITE);
        board.put(whiteAnchor2, Piece.WHITE);
        board.put(whitePool.get(0), Piece.WHITE);
        board.put(blackAnchor1, Piece.BLACK);
        board.put(blackAnchor2, Piece.BLACK);
        board.put(blackPool.get(0), Piece.BLACK);
        GameState initial = new GameState(board, Player.WHITE, 0, 0, false, null, 0, Map.of(), 0, null);
        GameEngine engine = GameEngine.restore(initial);

        BoardPosition whiteFlyerAt = whitePool.get(0);
        BoardPosition blackFlyerAt = blackPool.get(0);
        int whiteTurns = 0;
        int blackTurns = 0;
        GameState state = engine.state();
        int ply = 0;
        while (state.drawReason() == null && ply < 50) {
            boolean whiteTurn = state.currentPlayer() == Player.WHITE;
            BoardPosition from = whiteTurn ? whiteFlyerAt : blackFlyerAt;
            BoardPosition to = whiteTurn
                    ? whitePool.get((whiteTurns + 1) % whitePool.size())
                    : blackPool.get((blackTurns + 1) % blackPool.size());
            state = engine.apply(new GameAction(ActionType.MOVE, from, to));
            engine = GameEngine.restore(state);
            if (whiteTurn) { whiteFlyerAt = to; whiteTurns++; } else { blackFlyerAt = to; blackTurns++; }
            ply++;
        }

        assertThat(state.drawReason()).isEqualTo("DRAW_NO_CAPTURE");
        assertThat(state.pliesSinceRemoval()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void placingPhaseNeverCountsTowardRepetitionEvenWithIdenticalBoards() {
        // Nine placements each leaves nine distinct piece counts along the way - positionCounts
        // must stay empty until both players have placed all nine pieces (spec M2.5).
        GameEngine engine = GameEngine.newGame();
        GameState state = engine.apply(new GameAction(ActionType.PLACE, null, BoardPosition.A1));
        assertThat(state.positionCounts()).isEmpty();
    }

    @Test
    void captureRecordsThePostCapturePositionForRepetitionCounting() {
        // Regression for a review finding: GameEngine.remove() used to skip evaluateDraw()
        // entirely, so the position immediately after a capture was never added to
        // positionCounts. If that exact post-capture position later recurred via non-capturing
        // moves, it would take a 4th real occurrence (instead of 3) to trigger DRAW_REPETITION -
        // diverging from spec ("每步之后计数加 1", no exception for a capturing ply).
        // White already holds the (A1, D1, G1) outer-ring mill (removalPending = true); Black
        // starts with 4 pieces (B2, F2, F6, B6 - none of which are in a mill, per
        // BoardTopology.mills(), since each of the four middle-ring mills needs one of
        // D2/F4/D6/B4, which Black doesn't hold) so that after one is captured, Black still has
        // 3 pieces left (not a loss) and can fly (so always has legal moves).
        EnumMap<BoardPosition, Piece> board = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) board.put(position, Piece.EMPTY);
        board.put(BoardPosition.A1, Piece.WHITE);
        board.put(BoardPosition.D1, Piece.WHITE);
        board.put(BoardPosition.G1, Piece.WHITE);
        board.put(BoardPosition.G7, Piece.WHITE);
        board.put(BoardPosition.B2, Piece.BLACK);
        board.put(BoardPosition.F2, Piece.BLACK);
        board.put(BoardPosition.F6, Piece.BLACK);
        board.put(BoardPosition.B6, Piece.BLACK);

        GameState removalPendingState = new GameState(board, Player.WHITE, 0, 0, true, null, 0, Map.of(), 0, null);
        GameEngine engine = GameEngine.restore(removalPendingState);

        GameState state = engine.apply(GameAction.remove(BoardPosition.B2));

        assertThat(state.winner()).isNull();
        assertThat(state.drawReason()).isNull();
        assertThat(state.positionCounts()).hasSize(1);
        assertThat(state.positionCounts().values()).containsExactly(1);
    }

    private static GameEngine minimalMovingPhasePosition() {
        EnumMap<BoardPosition, Piece> board = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) {
            board.put(position, Piece.EMPTY);
        }
        board.put(BoardPosition.A1, Piece.WHITE);
        board.put(BoardPosition.G7, Piece.WHITE);
        board.put(BoardPosition.B2, Piece.WHITE);
        board.put(BoardPosition.F2, Piece.WHITE);
        board.put(BoardPosition.D7, Piece.BLACK);
        board.put(BoardPosition.G4, Piece.BLACK);
        board.put(BoardPosition.C3, Piece.BLACK);
        board.put(BoardPosition.E3, Piece.BLACK);
        // whitePiecesToPlace/blackPiecesToPlace = 0 puts both sides in the moving phase immediately.
        // NOTE (implementer correction vs. the brief): the brief's original fixture gave each side
        // only 2 pieces total (shuttle + one stationary anchor). GameEngine.finishTurn() ends the
        // game the moment the player to move has fewer than 3 pieces on board - with only 2 pieces
        // per side that fires on white's very first move, before any repetition can accumulate
        // (confirmed by running the test: it threw GameRuleException("The game is already over") on
        // the second engine.apply() call). Each side now has 4 pieces (shuttle + three stationary
        // anchors) so piecesOnBoard() is always 4 - never < 3 (no instant loss) and never == 3 (no
        // flying phase, so BoardTopology adjacency still governs the shuttle moves as intended).
        // A1<->A4 and D7<->D6 are still the confirmed-adjacent shuttle pairs. The extra anchors
        // (white: G7, B2, F2; black: G4, C3, E3) were checked against every mill in
        // BoardTopology.mills() together with both shuttle endpoints: no combination of a shuttle
        // position (A1 or A4 for white; D7 or D6 for black) plus that side's three anchors ever
        // supplies all three points of any mill, so no capture ever interrupts the repetition.
        GameState moving = new GameState(board, Player.WHITE, 0, 0, false, null, 0, Map.of(), 0, null);
        return GameEngine.restore(moving);
    }
}
