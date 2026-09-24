package io.github.hannnz1.morris.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class GameEngine {

    private final EnumMap<BoardPosition, Piece> board;
    private Player currentPlayer;
    private int whitePiecesToPlace;
    private int blackPiecesToPlace;
    private boolean removalPending;
    private Player winner;
    private long turnNumber;
    private final Map<String, Integer> positionCounts;
    private int pliesSinceRemoval;
    private String drawReason;

    private GameEngine(GameState state) {
        this.board = new EnumMap<>(state.board());
        this.currentPlayer = state.currentPlayer();
        this.whitePiecesToPlace = state.whitePiecesToPlace();
        this.blackPiecesToPlace = state.blackPiecesToPlace();
        this.removalPending = state.removalPending();
        this.winner = state.winner();
        this.turnNumber = state.turnNumber();
        this.positionCounts = new java.util.HashMap<>(state.positionCounts());
        this.pliesSinceRemoval = state.pliesSinceRemoval();
        this.drawReason = state.drawReason();
    }

    public static GameEngine newGame() {
        EnumMap<BoardPosition, Piece> emptyBoard = new EnumMap<>(BoardPosition.class);
        for (BoardPosition position : BoardPosition.values()) {
            emptyBoard.put(position, Piece.EMPTY);
        }
        return new GameEngine(new GameState(emptyBoard, Player.WHITE, 9, 9, false, null, 0, Map.of(), 0, null));
    }

    public static GameEngine restore(GameState state) {
        return new GameEngine(state);
    }

    public GameState state() {
        return new GameState(board, currentPlayer, whitePiecesToPlace, blackPiecesToPlace,
                removalPending, winner, turnNumber, positionCounts, pliesSinceRemoval, drawReason);
    }

    public GameState apply(GameAction action) {
        if (action == null) {
            throw new GameRuleException("Action is required");
        }
        if (winner != null || drawReason != null) {
            throw new GameRuleException("The game is already over");
        }
        if (removalPending && action.type() != ActionType.REMOVE) {
            throw new GameRuleException("An opponent piece must be removed before the turn can continue");
        }
        if (!removalPending && action.type() == ActionType.REMOVE) {
            throw new GameRuleException("A piece can only be removed after forming a mill");
        }

        switch (action.type()) {
            case PLACE -> place(action.to());
            case MOVE -> move(action.from(), action.to());
            case REMOVE -> remove(action.to());
        }
        return state();
    }

    public Set<BoardPosition> legalPlacements() {
        if (winner != null || drawReason != null || removalPending || piecesToPlace(currentPlayer) == 0) {
            return Set.of();
        }
        EnumSet<BoardPosition> targets = EnumSet.noneOf(BoardPosition.class);
        board.forEach((position, piece) -> {
            if (piece == Piece.EMPTY) {
                targets.add(position);
            }
        });
        return Collections.unmodifiableSet(targets);
    }

    public Map<BoardPosition, Set<BoardPosition>> legalMoves() {
        if (winner != null || drawReason != null || removalPending || piecesToPlace(currentPlayer) > 0) {
            return Map.of();
        }
        return legalMovesFor(currentPlayer);
    }

    public Set<BoardPosition> removablePieces() {
        if (!removalPending || winner != null || drawReason != null) {
            return Set.of();
        }

        Player opponent = currentPlayer.opponent();
        EnumSet<BoardPosition> allOpponentPieces = positionsOwnedBy(opponent);
        EnumSet<BoardPosition> outsideMills = EnumSet.copyOf(allOpponentPieces);
        outsideMills.removeIf(position -> isInMill(position, opponent));
        Set<BoardPosition> result = outsideMills.isEmpty() ? allOpponentPieces : outsideMills;
        return Collections.unmodifiableSet(result);
    }

    private void place(BoardPosition destination) {
        requirePosition(destination, "Placement destination is required");
        if (piecesToPlace(currentPlayer) == 0) {
            throw new GameRuleException("All pieces for this player have already been placed");
        }
        requireEmpty(destination);

        board.put(destination, currentPlayer.piece());
        decrementPiecesToPlace(currentPlayer);
        completePlacementOrMove(destination);
    }

    private void move(BoardPosition source, BoardPosition destination) {
        requirePosition(source, "Move source is required");
        requirePosition(destination, "Move destination is required");
        if (piecesToPlace(currentPlayer) > 0) {
            throw new GameRuleException("All pieces must be placed before movement begins");
        }
        if (!board.get(source).belongsTo(currentPlayer)) {
            throw new GameRuleException("The source does not contain the current player's piece");
        }
        requireEmpty(destination);

        boolean canFly = piecesOnBoard(currentPlayer) == 3;
        if (!canFly && !BoardTopology.areAdjacent(source, destination)) {
            throw new GameRuleException("Pieces must move to an adjacent empty position");
        }

        board.put(source, Piece.EMPTY);
        board.put(destination, currentPlayer.piece());
        completePlacementOrMove(destination);
    }

    private void remove(BoardPosition target) {
        requirePosition(target, "Removal target is required");
        if (!removablePieces().contains(target)) {
            throw new GameRuleException("The selected opponent piece cannot be removed");
        }

        board.put(target, Piece.EMPTY);
        removalPending = false;
        pliesSinceRemoval = 0; // a capture always resets the no-capture counter, win or not
        Player opponent = currentPlayer.opponent();
        if (allPiecesPlaced() && (piecesOnBoard(opponent) < 3 || legalMovesFor(opponent).isEmpty())) {
            winner = currentPlayer;
            turnNumber++;
            return;
        }
        Player playerWhoMoved = currentPlayer;
        currentPlayer = currentPlayer.opponent();
        turnNumber++;
        if (allPiecesPlaced()
                && (piecesOnBoard(currentPlayer) < 3 || legalMovesFor(currentPlayer).isEmpty())) {
            winner = playerWhoMoved;
        }
        // pliesSinceRemoval already reset above; a capture cannot also be the move that completes
        // a threefold repetition of the position it just changed, so only the no-capture check
        // would ever apply here, and it can't trigger the same ply it was reset - no evaluateDraw().
    }

    private void completePlacementOrMove(BoardPosition destination) {
        if (isInMill(destination, currentPlayer)) {
            removalPending = true;
            return;
        }
        finishTurn();
    }

    private void finishTurn() {
        Player playerWhoMoved = currentPlayer;
        currentPlayer = currentPlayer.opponent();
        turnNumber++;
        if (allPiecesPlaced()
                && (piecesOnBoard(currentPlayer) < 3 || legalMovesFor(currentPlayer).isEmpty())) {
            winner = playerWhoMoved;
            return;
        }
        if (allPiecesPlaced()) {
            pliesSinceRemoval++;
            evaluateDraw();
        }
    }

    private void evaluateDraw() {
        String key = positionKey();
        int count = positionCounts.merge(key, 1, Integer::sum);
        if (count >= 3) {
            drawReason = "DRAW_REPETITION";
            return;
        }
        if (pliesSinceRemoval >= 50) {
            drawReason = "DRAW_NO_CAPTURE";
        }
    }

    private String positionKey() {
        StringBuilder key = new StringBuilder(64);
        for (BoardPosition position : BoardPosition.values()) {
            key.append(board.get(position).name().charAt(0));
        }
        key.append('|').append(currentPlayer).append('|').append(whitePiecesToPlace).append(',').append(blackPiecesToPlace);
        return key.toString();
    }

    private Map<BoardPosition, Set<BoardPosition>> legalMovesFor(Player player) {
        EnumSet<BoardPosition> emptyPositions = positionsWith(Piece.EMPTY);
        boolean canFly = piecesOnBoard(player) == 3;
        Map<BoardPosition, Set<BoardPosition>> moves = new LinkedHashMap<>();

        for (BoardPosition source : positionsOwnedBy(player)) {
            EnumSet<BoardPosition> destinations = EnumSet.noneOf(BoardPosition.class);
            if (canFly) {
                destinations.addAll(emptyPositions);
            } else {
                for (BoardPosition neighbour : BoardTopology.neighboursOf(source)) {
                    if (board.get(neighbour) == Piece.EMPTY) {
                        destinations.add(neighbour);
                    }
                }
            }
            if (!destinations.isEmpty()) {
                moves.put(source, Collections.unmodifiableSet(destinations));
            }
        }
        return Collections.unmodifiableMap(moves);
    }

    private boolean isInMill(BoardPosition position, Player player) {
        return BoardTopology.mills().stream()
                .filter(mill -> mill.contains(position))
                .anyMatch(mill -> mill.stream().allMatch(point -> board.get(point).belongsTo(player)));
    }

    private EnumSet<BoardPosition> positionsOwnedBy(Player player) {
        return positionsWith(player.piece());
    }

    private EnumSet<BoardPosition> positionsWith(Piece expected) {
        EnumSet<BoardPosition> positions = EnumSet.noneOf(BoardPosition.class);
        board.forEach((position, piece) -> {
            if (piece == expected) {
                positions.add(position);
            }
        });
        return positions;
    }

    private int piecesOnBoard(Player player) {
        return positionsOwnedBy(player).size();
    }

    private int piecesToPlace(Player player) {
        return player == Player.WHITE ? whitePiecesToPlace : blackPiecesToPlace;
    }

    private void decrementPiecesToPlace(Player player) {
        if (player == Player.WHITE) {
            whitePiecesToPlace--;
        } else {
            blackPiecesToPlace--;
        }
    }

    private boolean allPiecesPlaced() {
        return whitePiecesToPlace == 0 && blackPiecesToPlace == 0;
    }

    private void requireEmpty(BoardPosition position) {
        if (board.get(position) != Piece.EMPTY) {
            throw new GameRuleException("The destination position is occupied");
        }
    }

    private static void requirePosition(BoardPosition position, String message) {
        if (position == null) {
            throw new GameRuleException(message);
        }
    }
}

