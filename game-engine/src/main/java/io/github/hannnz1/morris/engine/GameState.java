package io.github.hannnz1.morris.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record GameState(
        Map<BoardPosition, Piece> board,
        Player currentPlayer,
        int whitePiecesToPlace,
        int blackPiecesToPlace,
        boolean removalPending,
        Player winner,
        long turnNumber
) {
    public GameState {
        if (board == null || currentPlayer == null) {
            throw new IllegalArgumentException("Board and current player are required");
        }
        if (whitePiecesToPlace < 0 || whitePiecesToPlace > 9
                || blackPiecesToPlace < 0 || blackPiecesToPlace > 9) {
            throw new IllegalArgumentException("Pieces to place must be between zero and nine");
        }
        if (turnNumber < 0) {
            throw new IllegalArgumentException("Turn number cannot be negative");
        }

        EnumMap<BoardPosition, Piece> copy = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) {
            Piece piece = board.get(position);
            if (piece == null) {
                throw new IllegalArgumentException("Missing board position: " + position);
            }
            copy.put(position, piece);
        }
        board = Collections.unmodifiableMap(copy);

        if (winner != null && removalPending) {
            throw new IllegalArgumentException("A completed game cannot await removal");
        }
    }

    public int piecesToPlace(Player player) {
        return player == Player.WHITE ? whitePiecesToPlace : blackPiecesToPlace;
    }

    public int piecesOnBoard(Player player) {
        return (int) board.values().stream().filter(piece -> piece.belongsTo(player)).count();
    }

    public GamePhase phase() {
        if (winner != null) {
            return GamePhase.GAME_OVER;
        }
        if (removalPending) {
            return GamePhase.REMOVE;
        }
        if (piecesToPlace(currentPlayer) > 0) {
            return GamePhase.PLACING;
        }
        return piecesOnBoard(currentPlayer) == 3 ? GamePhase.FLYING : GamePhase.MOVING;
    }
}

