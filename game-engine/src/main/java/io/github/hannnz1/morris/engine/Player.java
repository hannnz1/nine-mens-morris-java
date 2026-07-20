package io.github.hannnz1.morris.engine;

public enum Player {
    WHITE,
    BLACK;

    public Player opponent() {
        return this == WHITE ? BLACK : WHITE;
    }

    public Piece piece() {
        return this == WHITE ? Piece.WHITE : Piece.BLACK;
    }
}

