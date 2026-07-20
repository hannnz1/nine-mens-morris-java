package Logic;

import Manager.CandidateMgr;
import Manager.HintManager;
import View.Line3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.lang.System.exit;

/**
 The Game class maintains the game state, tracks the current player's turn (isWhiteRound), the number of tokens
 aside for each player (whiteAside and blackAside), and the number of tokens out of the board (whiteOut and blackOut).

 The class also contains methods for handling player clicks on positions, updating the game state, checking for a
 game over condition, getting the winner, and managing the selection and movement of tokens.
 **/
public class Game {
    public ArrayList<Position> positions; // List of positions on the game board
    public Map<Position, ArrayList<Position>> edges; // Map of positions and their neighboring positions

    public Position selectedPos = null; // Currently selected position

    public boolean isWhiteRound = true; // Indicates if it's white player's turn
    private int whiteAside = 9; // Number of white tokens remaining to be placed on the board
    private int blackAside = 9; // Number of black tokens remaining to be placed on the board

    private int whiteOut = 0; // Number of white tokens that have been kicked off the board
    private int blackOut = 0; // Number of black tokens that have been kicked off the board
    boolean isWaitForKick = false; // Indicates if a player needs to kick off an opponent's token

    private Map<Position, ArrayList<Line3>> straightLines = new HashMap<>(); // Map of positions and the lines they belong to
    public ArrayList<Line3> formedLines = new ArrayList<>(); // List of lines that have been formed on the board


    public Game(ArrayList<Position> positions, Map<Position, ArrayList<Position>> edges) {
        this.positions = positions;
        this.edges = edges;
        initLines(); // Initialize the lines on the board
    }

    private void initLines() {
        //Initializes the lines used for forming mills on the game board
        for (int round = 0; round < 3; round ++) {
            int[] startIndexes = {0, 2, 4, 6};
            for (var start : startIndexes) {
                ArrayList<Position> line = new ArrayList<>();
                for (int inc = 0; inc < 3; inc++) { // generate 1 line
                    int ind = start + inc;
                    ind = ind % 8;
                    ind += round * 8;
                    line.add(positions.get(ind));
                }

                for (int inc = 0; inc < 3; inc++) {
                    int ind = start + inc;
                    ind = ind % 8;
                    ind += round * 8;
                    Position p = positions.get(ind);
                    if (getStraightLines().containsKey(p)) {
                        getStraightLines().get(p).add(new Line3(line));
                    } else {
                        var lines = new ArrayList<Line3>();
                        lines.add(new Line3(line));
                        getStraightLines().put(p, lines);
                    }
                }
            }
        }

        int[] midIndexes = {1, 3, 5, 7};
        for (var start : midIndexes) {
            ArrayList<Position> line = new ArrayList<>();
            for (int round = 0; round < 3; round ++) {
                int index = start + round * 8;
                line.add(positions.get(index));
            }

            for (int round = 0; round < 3; round ++) {
                int index = start + round * 8;
                Position p = positions.get(index);
                getStraightLines().get(p).add(new Line3(line));
            }
        }

    }

    // Checks if the game is over (no more valid moves)
    public boolean gameOver() {
        return CandidateMgr.getInstance().getCandidates().size() == 0;
    }

    public String getWinner() {
        // Returns the winner of the game
        if (isWhiteRound) {
            return "BLACK WINS";
        }

        return "WHITE WINS";
    }

    // Update method called in each game loop iteration
    public void update(double time_step) {
    }

    private int getIndexByDir(String dir) {
        String[] orderList = {"nn", "n0", "np", "0p", "pp", "p0", "pn", "0n"};
        for (int i = 0; i < orderList.length; i ++) {
            if (dir.equals(orderList[i])) {
                return i;
            }
        }
        System.out.println("Not found:" + dir);
        return -1;
    }

    private int getIndexByString(String s) { //0,n0
        String[] l = s.split(",");
        int circleIndex = Integer.parseInt(l[0]);
        int innerIndex = getIndexByDir(l[1]);
        if (innerIndex < 0) {
            System.out.println("ERROR: invalid debug position");
            exit(1);
        }

        return circleIndex * 8 + innerIndex;
    }

    public void debugInit() {
        this.gatherCandidates(null);

        boolean isDebug = true;
        if (!isDebug) {
            return;
        }

        // init aside
        whiteAside = 9;
        blackAside = 9;
        whiteOut = 9 - whiteAside;
        blackOut = 9 - blackAside;


        this.gatherCandidates(null);
    }



    public boolean isWhiteRound() {
        return isWhiteRound;
    }

    public int getWhiteAside() {
        return whiteAside;
    }

    public int getBlackAside() {
        return blackAside;
    }

    public int getWhiteOut() {
        return whiteOut;
    }

    public int getBlackOut() {
        return blackOut;
    }

    public void clickedPos(Position releasePos) {


        if (isWhiteRound) {
            handleWhiteClick(releasePos);
        } else {
            handleBlackClick(releasePos);
        }

        ArrayList<Line3> removed = new ArrayList<>();
        for (var line : formedLines) {
            if (!line.isConnectedLine()) {
                removed.add(line);
            }
        }

        for (var line : removed) {
            formedLines.remove(line);
        }
    }

    // Handles a click on a position by a player with black tokens
    private void handleBlackClick(Position clickedPos) {
        if (isWaitForKick) {
            kick(clickedPos, PositionStatus.WHITE); // kick component
            return;
        }

        if (blackAside > 0) { // put
            if (clickedPos.getStatus() == PositionStatus.EMPTY) {
                blackAside--;
                clickedPos.setStatus(PositionStatus.BLACK);
                checkFormedNewLineAndUpdate(clickedPos, PositionStatus.BLACK);
            }
        } else {
            if (selectedPos == null) {
                if (clickedPos.getStatus() == PositionStatus.BLACK) {
                    select(clickedPos);
                }
            } else {
                if (clickedPos.getStatus() == PositionStatus.EMPTY) {
                    moveSelected(clickedPos, PositionStatus.BLACK);
                }

            }
        }
    }

    // Handles a click on a position by a player with white tokens
    private void handleWhiteClick(Position clickedPos) {
        if (isWaitForKick) {
            kick(clickedPos, PositionStatus.BLACK); // kick component
            return;
        }

        if (whiteAside > 0) { // put
            if (clickedPos.getStatus() == PositionStatus.EMPTY) {
                whiteAside--;
                clickedPos.setStatus(PositionStatus.WHITE);
                checkFormedNewLineAndUpdate(clickedPos, PositionStatus.WHITE);
            }
        } else {
            if (selectedPos == null) {
                if (clickedPos.getStatus() == PositionStatus.WHITE) {
                    select(clickedPos);
                }
            } else {
                if (clickedPos.getStatus() == PositionStatus.EMPTY) {
                    moveSelected(clickedPos, PositionStatus.WHITE);
                }

            }
        }
    }
    public String opPrompt = "";
    private void gatherCandidates(Position pos) {
        // Sets the prompt for the current player's turn
        if (isWaitForKick) {
            CandidateMgr.getInstance().gatherKickCandidates(this);
            opPrompt = "Select one enemy token to kick off!";
            return;
        }

        if (isWhiteRound() && whiteAside > 0) {
            CandidateMgr.getInstance().gatherPutCandidates(this);
            opPrompt = "Select one position to place your token!";
        } else if (!isWhiteRound() && blackAside > 0) {
            CandidateMgr.getInstance().gatherPutCandidates(this);
            opPrompt = "Select one position to place your token!";
        } else {
            if (selectedPos == null) {
                CandidateMgr.getInstance().gatherSelectCandidates(this);
                opPrompt = "Select one token you want to move!";
            } else {
                CandidateMgr.getInstance().gatherMoveCandidates(this, pos);
                opPrompt = "Select where you want to move the token to!";
            }
        }
    }

    // Kicks off an enemy token from the board
    private void kick(Position clickedPos, PositionStatus enemyColor) {
        if (CandidateMgr.getInstance().getCandidates().contains(clickedPos)) {
            clickedPos.setStatus(PositionStatus.EMPTY);

            if (enemyColor == PositionStatus.WHITE) {
                whiteOut++;
            } else {
                blackOut++;
            }

            isWaitForKick = false;
            isWhiteRound = !isWhiteRound;

            gatherCandidates(null);
        }
    }

    // Moves the currently selected token to the clicked position
    private void moveSelected(Position clickedPos, PositionStatus sta) {
        if (clickedPos == null ||
                !CandidateMgr.getInstance().getCandidates().contains(clickedPos)) {
            return;
        }

        selectedPos.setStatus(PositionStatus.EMPTY);
        selectedPos = null;
        clickedPos.setStatus(sta);
        checkFormedNewLineAndUpdate(clickedPos, sta);

    }

    private boolean checkFormedNewLineAndUpdate(Position clickedPos, PositionStatus sta) {
        boolean linked = false;
        // judge......
        var lines = getStraightLines().get(clickedPos);
        for (var line : lines) {
            if (line.formedOneLine(sta) &&
                    !formedLines.contains(line)) { // avoid duplicate
                formedLines.add(line);
                linked = true;
            }
        }

        if (linked) {
            isWaitForKick = true;
        } else {
            isWhiteRound = !isWhiteRound;
        }

        this.gatherCandidates(null);
        return linked;
    }


    private void select(Position clickedPos) {
        if (clickedPos == null) {
            return;
        }

        if (!CandidateMgr.getInstance().getCandidates().contains(clickedPos)) {
            HintManager.getInstance().newTips("Can not select this token to move!");
            return;
        }

        selectedPos = clickedPos;
        this.gatherCandidates(clickedPos);
    }

    public boolean hasAnyEmptyNeighbor(Position clickedPos) {
        var neighList = edges.get(clickedPos);
        for (var n : neighList) {
            if (n.getStatus() == PositionStatus.EMPTY) {
                return true;
            }
        }
        return false;
    }

    public boolean isAMillPieceInLine3(Position p) {
        for (var l : formedLines) {
            if (l.contains(p)) {
                return true;
            }
        }

        return false;
    }

    public Map<Position, ArrayList<Line3>> getStraightLines() {
        return straightLines;
    }

    public void setStraightLines(Map<Position, ArrayList<Line3>> straightLines) {
        this.straightLines = straightLines;
    }

    public String getSide() {
        if (isWhiteRound) {
            return "WHITE: ";
        }

        return "BLACK: ";
    }
}
