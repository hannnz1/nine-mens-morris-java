package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import java.util.*;
public final class PositionEvaluator {
    private static final BoardPosition[] POSITIONS = BoardPosition.values();
    private static final BoardPosition[][] MILLS = BoardTopology.mills().stream()
            .map(m -> m.toArray(BoardPosition[]::new)).toArray(BoardPosition[][]::new);
    public int evaluate(GameState state, Player perspective) {
        return score(state, perspective) - score(state, perspective.opponent());
    }
    private int score(GameState s, Player player) {
        var board = s.board();
        int count = 0, empty = 0, mobility = 0, blocked = 0, mills = 0, threats = 0;
        for (var p : POSITIONS) {
            if (board.get(p) == Piece.EMPTY) empty++;
            if (board.get(p).belongsTo(player)) count++;
        }
        for (var p : POSITIONS) if (board.get(p).belongsTo(player)) {
            int moves = 0;
            for (var n : BoardTopology.neighboursOf(p)) if (board.get(n) == Piece.EMPTY) moves++;
            mobility += moves;
            if (moves == 0) blocked++;
        }
        if (s.piecesToPlace(player) > 0) mobility = empty;
        else if (count == 3) { mobility = count * empty; blocked = empty == 0 ? count : 0; }
        for (var mill : MILLS) {
            int owned = 0, free = 0;
            for (var p : mill) { if (board.get(p).belongsTo(player)) owned++; else if (board.get(p) == Piece.EMPTY) free++; }
            if (owned == 3) mills++;
            if (owned == 2 && free == 1) threats++;
        }
        // Include reserves: placement transfers a piece onto the board, capture actually loses one.
        return (count + s.piecesToPlace(player)) * 100 + mills * 30 + mobility * 5 - blocked * 10 + threats * 15;
    }
    static int order(GameState s, GameAction action) {
        if (action.type() == ActionType.REMOVE) return 0;
        int score = 0;
        for (var mill : MILLS) {
            boolean contains = false; int mine = 0, theirs = 0;
            for (var p : mill) {
                if (p == action.to()) { contains = true; continue; }
                if (p == action.from()) continue;
                if (s.board().get(p).belongsTo(s.currentPlayer())) mine++;
                if (s.board().get(p).belongsTo(s.currentPlayer().opponent())) theirs++;
            }
            if (contains && mine == 2) score += 100;
            if (contains && theirs == 2) score += 50;
        }
        return score;
    }
}
