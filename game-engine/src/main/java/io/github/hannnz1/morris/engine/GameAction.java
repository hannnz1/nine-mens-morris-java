package io.github.hannnz1.morris.engine;

public record GameAction(ActionType type, BoardPosition from, BoardPosition to) {

    public GameAction {
        if (type == null) {
            throw new IllegalArgumentException("Action type is required");
        }
    }

    public static GameAction place(BoardPosition to) {
        return new GameAction(ActionType.PLACE, null, to);
    }

    public static GameAction move(BoardPosition from, BoardPosition to) {
        return new GameAction(ActionType.MOVE, from, to);
    }

    public static GameAction remove(BoardPosition target) {
        return new GameAction(ActionType.REMOVE, null, target);
    }
}

