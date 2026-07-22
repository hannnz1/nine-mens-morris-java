package Logic;

import Manager.CandidateMgr;
import Manager.HintManager;
import View.Line3;
import io.github.hannnz1.morris.engine.BoardPosition;
import io.github.hannnz1.morris.engine.BoardTopology;
import io.github.hannnz1.morris.engine.GameAction;
import io.github.hannnz1.morris.engine.GameEngine;
import io.github.hannnz1.morris.engine.GamePhase;
import io.github.hannnz1.morris.engine.Piece;
import io.github.hannnz1.morris.engine.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter between the original desktop UI and the shared, framework-independent
 * rules engine. The UI still works with drawable {@link Position} objects, but
 * every state transition and legal-action decision is owned by {@link GameEngine}.
 */
public class Game {

    private static final BoardPosition[] UI_ORDER = {
            BoardPosition.A1, BoardPosition.A4, BoardPosition.A7, BoardPosition.D7,
            BoardPosition.G7, BoardPosition.G4, BoardPosition.G1, BoardPosition.D1,
            BoardPosition.B2, BoardPosition.B4, BoardPosition.B6, BoardPosition.D6,
            BoardPosition.F6, BoardPosition.F4, BoardPosition.F2, BoardPosition.D2,
            BoardPosition.C3, BoardPosition.C4, BoardPosition.C5, BoardPosition.D5,
            BoardPosition.E5, BoardPosition.E4, BoardPosition.E3, BoardPosition.D3
    };

    public final ArrayList<Position> positions;
    public final Map<Position, ArrayList<Position>> edges;
    public Position selectedPos;
    public boolean isWhiteRound = true;
    public final ArrayList<Line3> formedLines = new ArrayList<>();
    public String opPrompt = "";

    private final EnumMap<BoardPosition, Position> uiByBoard =
            new EnumMap<>(BoardPosition.class);
    private final Map<Position, BoardPosition> boardByUi = new HashMap<>();
    private GameEngine engine = GameEngine.newGame();

    public Game(ArrayList<Position> positions, Map<Position, ArrayList<Position>> edges) {
        if (positions.size() != UI_ORDER.length) {
            throw new IllegalArgumentException("The desktop board must contain 24 positions");
        }
        this.positions = positions;
        this.edges = edges;
        for (int index = 0; index < UI_ORDER.length; index++) {
            uiByBoard.put(UI_ORDER[index], positions.get(index));
            boardByUi.put(positions.get(index), UI_ORDER[index]);
        }
        syncUi();
        gatherCandidates();
    }

    public boolean gameOver() {
        return engine.state().winner() != null;
    }

    public String getWinner() {
        Player winner = engine.state().winner();
        return winner == null ? "" : winner.name() + " WINS";
    }

    public void update(double timeStep) {
        // The desktop UI is event driven; no timed domain update is required.
    }

    public void debugInit() {
        engine = GameEngine.newGame();
        selectedPos = null;
        syncUi();
        gatherCandidates();
    }

    public boolean isWhiteRound() {
        return engine.state().currentPlayer() == Player.WHITE;
    }

    public int getWhiteAside() {
        return engine.state().whitePiecesToPlace();
    }

    public int getBlackAside() {
        return engine.state().blackPiecesToPlace();
    }

    public int getWhiteOut() {
        return 9 - getWhiteAside() - engine.state().piecesOnBoard(Player.WHITE);
    }

    public int getBlackOut() {
        return 9 - getBlackAside() - engine.state().piecesOnBoard(Player.BLACK);
    }

    public void clickedPos(Position clicked) {
        BoardPosition target = boardByUi.get(clicked);
        if (target == null || gameOver()) {
            return;
        }

        GamePhase phase = engine.state().phase();
        if (phase == GamePhase.REMOVE) {
            if (engine.removablePieces().contains(target)) {
                apply(GameAction.remove(target));
            }
            return;
        }

        if (phase == GamePhase.PLACING) {
            if (engine.legalPlacements().contains(target)) {
                apply(GameAction.place(target));
            }
            return;
        }

        Map<BoardPosition, java.util.Set<BoardPosition>> legalMoves = engine.legalMoves();
        if (selectedPos == null) {
            if (legalMoves.containsKey(target)) {
                selectedPos = clicked;
                gatherCandidates();
            } else {
                HintManager.getInstance().newTips("Can not select this token to move!");
            }
            return;
        }

        BoardPosition source = boardByUi.get(selectedPos);
        if (legalMoves.getOrDefault(source, java.util.Set.of()).contains(target)) {
            apply(GameAction.move(source, target));
        } else if (legalMoves.containsKey(target)) {
            selectedPos = clicked;
            gatherCandidates();
        }
    }

    private void apply(GameAction action) {
        engine.apply(action);
        selectedPos = null;
        syncUi();
        gatherCandidates();
    }

    private void syncUi() {
        engine.state().board().forEach((position, piece) ->
                uiByBoard.get(position).setStatus(toUiStatus(piece)));
        isWhiteRound = isWhiteRound();
        formedLines.clear();
        for (var mill : BoardTopology.mills()) {
            Piece first = engine.state().board().get(mill.iterator().next());
            if (first != Piece.EMPTY
                    && mill.stream().allMatch(point -> engine.state().board().get(point) == first)) {
                List<Position> line = mill.stream().map(uiByBoard::get)
                        .collect(Collectors.toList());
                formedLines.add(new Line3(new ArrayList<>(line)));
            }
        }
    }

    private PositionStatus toUiStatus(Piece piece) {
        return switch (piece) {
            case WHITE -> PositionStatus.WHITE;
            case BLACK -> PositionStatus.BLACK;
            case EMPTY -> PositionStatus.EMPTY;
        };
    }

    private void gatherCandidates() {
        GamePhase phase = engine.state().phase();
        if (phase == GamePhase.REMOVE) {
            CandidateMgr.getInstance().setCandidates(toUiPositions(engine.removablePieces()));
            opPrompt = "Select one enemy token to kick off!";
        } else if (phase == GamePhase.PLACING) {
            CandidateMgr.getInstance().setCandidates(toUiPositions(engine.legalPlacements()));
            opPrompt = "Select one position to place your token!";
        } else if (phase == GamePhase.GAME_OVER) {
            CandidateMgr.getInstance().setCandidates(java.util.Set.of());
            opPrompt = "Game over";
        } else if (selectedPos == null) {
            CandidateMgr.getInstance().setCandidates(toUiPositions(engine.legalMoves().keySet()));
            opPrompt = "Select one token you want to move!";
        } else {
            BoardPosition source = boardByUi.get(selectedPos);
            CandidateMgr.getInstance().setCandidates(
                    toUiPositions(engine.legalMoves().getOrDefault(source, java.util.Set.of())));
            opPrompt = "Select where you want to move the token to!";
        }
    }

    private java.util.Set<Position> toUiPositions(java.util.Set<BoardPosition> boardPositions) {
        java.util.Set<Position> result = new java.util.HashSet<>();
        boardPositions.forEach(position -> result.add(uiByBoard.get(position)));
        return result;
    }

    // Compatibility helpers retained for the legacy hint classes.
    public boolean hasAnyEmptyNeighbor(Position position) {
        BoardPosition boardPosition = boardByUi.get(position);
        return BoardTopology.neighboursOf(boardPosition).stream()
                .anyMatch(point -> engine.state().board().get(point) == Piece.EMPTY);
    }

    public boolean isAMillPieceInLine3(Position position) {
        BoardPosition boardPosition = boardByUi.get(position);
        Piece piece = engine.state().board().get(boardPosition);
        return piece != Piece.EMPTY && BoardTopology.mills().stream()
                .filter(mill -> mill.contains(boardPosition))
                .anyMatch(mill -> mill.stream()
                        .allMatch(point -> engine.state().board().get(point) == piece));
    }

    public String getSide() {
        return isWhiteRound() ? "WHITE: " : "BLACK: ";
    }
}
