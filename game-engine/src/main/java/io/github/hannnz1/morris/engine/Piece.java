package io.github.hannnz1.morris.engine;

public enum Piece {
    EMPTY,
    WHITE,
    BLACK;

    public boolean belongsTo(Player player) {
        return this == player.piece();
    }
}

