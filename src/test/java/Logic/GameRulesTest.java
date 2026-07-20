package Logic;

import Manager.CandidateMgr;
import View.BoardView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRulesTest {

    private Game game;
    private ArrayList<Position> positions;
    private Map<Position, ArrayList<Position>> edges;

    @BeforeEach
    void setUp() {
        BoardView board = new BoardView();
        board.initBoard(0.64);
        positions = board.getPositions();
        edges = board.getEdges();
        game = new Game(positions, edges);
        game.debugInit();
    }

    @Test
    void boardUsesTwentyFourPositionsAndSymmetricEdges() {
        assertEquals(24, positions.size());
        assertEquals(24, edges.size());

        int directedEdgeCount = 0;
        for (Map.Entry<Position, ArrayList<Position>> entry : edges.entrySet()) {
            directedEdgeCount += entry.getValue().size();
            for (Position neighbour : entry.getValue()) {
                assertTrue(edges.get(neighbour).contains(entry.getKey()),
                        "Every board edge must be bidirectional");
            }
        }

        assertEquals(64, directedEdgeCount);
    }

    @Test
    void newGameAllowsPlacementOnEveryPosition() {
        assertTrue(game.isWhiteRound());
        assertEquals(9, game.getWhiteAside());
        assertEquals(9, game.getBlackAside());
        assertEquals(24, CandidateMgr.getInstance().getCandidates().size());
    }

    @Test
    void validPlacementChangesTheTurnAndPieceCounts() {
        Position target = positions.get(0);

        game.clickedPos(target);

        assertSame(PositionStatus.WHITE, target.getStatus());
        assertEquals(8, game.getWhiteAside());
        assertEquals(9, game.getBlackAside());
        assertFalse(game.isWhiteRound());
        assertEquals(23, CandidateMgr.getInstance().getCandidates().size());
    }

    @Test
    void occupiedPositionCannotBeUsedForPlacement() {
        Position occupied = positions.get(0);
        game.clickedPos(occupied);

        game.clickedPos(occupied);

        assertSame(PositionStatus.WHITE, occupied.getStatus());
        assertEquals(9, game.getBlackAside());
        assertFalse(game.isWhiteRound());
    }

    @Test
    void formingAMillRequiresRemovingAnEligibleOpponentPiece() {
        Position whiteOne = positions.get(0);
        Position whiteTwo = positions.get(1);
        Position whiteThree = positions.get(2);
        Position blackOne = positions.get(8);
        Position blackTwo = positions.get(9);

        game.clickedPos(whiteOne);
        game.clickedPos(blackOne);
        game.clickedPos(whiteTwo);
        game.clickedPos(blackTwo);
        game.clickedPos(whiteThree);

        assertTrue(game.isWhiteRound(), "White keeps the turn while choosing a piece to remove");
        assertEquals(2, CandidateMgr.getInstance().getCandidates().size());
        assertTrue(CandidateMgr.getInstance().getCandidates().contains(blackOne));
        assertTrue(CandidateMgr.getInstance().getCandidates().contains(blackTwo));

        game.clickedPos(blackOne);

        assertSame(PositionStatus.EMPTY, blackOne.getStatus());
        assertEquals(1, game.getBlackOut());
        assertFalse(game.isWhiteRound());
    }
}

