package io.github.hannnz1.morris.engine;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineTest {

    @Test
    void topologyContainsTwentyFourPositionsAndThirtyTwoBidirectionalEdges() {
        int directedEdges = 0;
        for (BoardPosition position : BoardPosition.values()) {
            directedEdges += BoardTopology.neighboursOf(position).size();
            for (BoardPosition neighbour : BoardTopology.neighboursOf(position)) {
                assertTrue(BoardTopology.neighboursOf(neighbour).contains(position));
            }
        }

        assertEquals(24, BoardPosition.values().length);
        assertEquals(64, directedEdges);
        assertEquals(16, BoardTopology.mills().size());
    }

    @Test
    void newGameStartsWithWhiteAndAllPositionsAvailable() {
        GameEngine engine = GameEngine.newGame();

        assertEquals(Player.WHITE, engine.state().currentPlayer());
        assertEquals(GamePhase.PLACING, engine.state().phase());
        assertEquals(24, engine.legalPlacements().size());
        assertEquals(9, engine.state().whitePiecesToPlace());
        assertEquals(9, engine.state().blackPiecesToPlace());
    }

    @Test
    void placementAlternatesPlayersAndRejectsOccupiedDestination() {
        GameEngine engine = GameEngine.newGame();

        engine.apply(GameAction.place(BoardPosition.A1));

        assertEquals(Piece.WHITE, engine.state().board().get(BoardPosition.A1));
        assertEquals(Player.BLACK, engine.state().currentPlayer());
        assertEquals(1, engine.state().turnNumber());
        assertThrows(GameRuleException.class,
                () -> engine.apply(GameAction.place(BoardPosition.A1)));
    }

    @Test
    void formingMillKeepsTurnUntilOpponentPieceIsRemoved() {
        GameEngine engine = GameEngine.newGame();
        engine.apply(GameAction.place(BoardPosition.A1));
        engine.apply(GameAction.place(BoardPosition.B2));
        engine.apply(GameAction.place(BoardPosition.D1));
        engine.apply(GameAction.place(BoardPosition.B4));

        engine.apply(GameAction.place(BoardPosition.G1));

        assertEquals(GamePhase.REMOVE, engine.state().phase());
        assertEquals(Player.WHITE, engine.state().currentPlayer());
        assertTrue(engine.removablePieces().contains(BoardPosition.B2));
        assertThrows(GameRuleException.class,
                () -> engine.apply(GameAction.place(BoardPosition.G4)));

        engine.apply(GameAction.remove(BoardPosition.B2));

        assertEquals(Piece.EMPTY, engine.state().board().get(BoardPosition.B2));
        assertEquals(Player.BLACK, engine.state().currentPlayer());
        assertFalse(engine.state().removalPending());
    }

    @Test
    void playerWithThreePiecesCanFlyToNonAdjacentPosition() {
        EnumMap<BoardPosition, Piece> board = emptyBoard();
        board.put(BoardPosition.A1, Piece.WHITE);
        board.put(BoardPosition.D1, Piece.WHITE);
        board.put(BoardPosition.G1, Piece.WHITE);
        board.put(BoardPosition.B2, Piece.BLACK);
        board.put(BoardPosition.F2, Piece.BLACK);
        board.put(BoardPosition.F6, Piece.BLACK);
        board.put(BoardPosition.B6, Piece.BLACK);

        GameEngine engine = GameEngine.restore(
                new GameState(board, Player.WHITE, 0, 0, false, null, 18));

        assertEquals(GamePhase.FLYING, engine.state().phase());
        assertTrue(engine.legalMoves().get(BoardPosition.A1).contains(BoardPosition.E5));

        engine.apply(GameAction.move(BoardPosition.A1, BoardPosition.E5));

        assertEquals(Piece.EMPTY, engine.state().board().get(BoardPosition.A1));
        assertEquals(Piece.WHITE, engine.state().board().get(BoardPosition.E5));
    }

    @Test
    void playerWithMoreThanThreePiecesMustMoveToAdjacentPosition() {
        EnumMap<BoardPosition, Piece> board = emptyBoard();
        board.put(BoardPosition.A1, Piece.WHITE);
        board.put(BoardPosition.D1, Piece.WHITE);
        board.put(BoardPosition.G1, Piece.WHITE);
        board.put(BoardPosition.A4, Piece.WHITE);
        board.put(BoardPosition.B2, Piece.BLACK);
        board.put(BoardPosition.F2, Piece.BLACK);
        board.put(BoardPosition.F6, Piece.BLACK);
        board.put(BoardPosition.B6, Piece.BLACK);

        GameEngine engine = GameEngine.restore(
                new GameState(board, Player.WHITE, 0, 0, false, null, 18));

        assertEquals(GamePhase.MOVING, engine.state().phase());
        assertThrows(GameRuleException.class,
                () -> engine.apply(GameAction.move(BoardPosition.A1, BoardPosition.E5)));
    }

    @Test
    void removingThirdOpponentPieceFinishesGame() {
        EnumMap<BoardPosition, Piece> board = emptyBoard();
        board.put(BoardPosition.A1, Piece.WHITE);
        board.put(BoardPosition.D1, Piece.WHITE);
        board.put(BoardPosition.G1, Piece.WHITE);
        board.put(BoardPosition.B2, Piece.BLACK);
        board.put(BoardPosition.F2, Piece.BLACK);
        board.put(BoardPosition.B6, Piece.BLACK);

        GameEngine engine = GameEngine.restore(
                new GameState(board, Player.WHITE, 0, 0, true, null, 24));
        engine.apply(GameAction.remove(BoardPosition.B2));

        assertEquals(Player.WHITE, engine.state().winner());
        assertEquals(GamePhase.GAME_OVER, engine.state().phase());
        assertEquals(25, engine.state().turnNumber());
    }

    private static EnumMap<BoardPosition, Piece> emptyBoard() {
        EnumMap<BoardPosition, Piece> board = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) {
            board.put(position, Piece.EMPTY);
        }
        return board;
    }
}

