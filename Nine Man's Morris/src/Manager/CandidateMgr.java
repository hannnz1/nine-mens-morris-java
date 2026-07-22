package Manager;

import Logic.Game;
import Logic.Position;
import Logic.PositionStatus;

import java.util.HashSet;
import java.util.Set;

public class CandidateMgr {

    //Instance of the class
    private static CandidateMgr tm = null;

    //The set for which the tokens can be put or tokens can be moved to or tokens can be removed from
    private Set<Position> candidates = new HashSet<>();

    //Here, we are using Singleton Design Pattern to make sure we only have one instance of CandidateMgr class.
    public static CandidateMgr getInstance() {
        if (tm == null) {
            tm = new CandidateMgr();
        }

        return tm;
    }

    //Helper function for validation of putting tokens in positions in the first phase of the game.
    public void gatherPutCandidates(Game g) {
        candidates.clear();
        for (var start : g.positions) {
            if (start.getStatus() == PositionStatus.EMPTY) {
                candidates.add(start);
            }
        }

    }

    ////Helper function for validation of removing tokens from positions in the second phase of the game.
    public void gatherKickCandidates(Game g) {
        candidates.clear();

        PositionStatus interestType = PositionStatus.WHITE;

        if (g.isWhiteRound) {
            interestType = PositionStatus.BLACK;
        }


        Set<Position> inline3 = new HashSet<>();
        for (var p : g.positions) {
            if (p.getStatus() == interestType) {
                if (g.isAMillPieceInLine3(p)) {
                    inline3.add(p);
                } else { // only isolated are good
                    candidates.add(p);
                }
            }
        }

        if (candidates.size() == 0) {
            candidates = inline3;
        }
    }

    ////Helper function for validation of putting tokens in positions in the first phase of the game.
    public void gatherSelectCandidates(Game g) {
        candidates.clear();
        int remaining = 9 - g.getBlackOut();
        if (g.isWhiteRound()) {
            remaining = 9 - g.getWhiteOut();
        }

        if (remaining < 3) {
            return;
        }


        if (g.isWhiteRound) {
            for (var start : g.positions) {
                if (start.getStatus() == PositionStatus.WHITE ) {
                    if (g.hasAnyEmptyNeighbor(start)||
                            remaining == 3) { //  can move to anywhere when only 3 left
                        candidates.add(start);
                    }
                }
            }
        } else {
            for (var start : g.positions) {
                if (start.getStatus() == PositionStatus.BLACK) {
                    if (g.hasAnyEmptyNeighbor(start)||
                            remaining == 3) { // can move to anywhere when only 3 left
                        candidates.add(start);
                    }
                }
            }
        }


    }

    ////Helper function for validation of moving tokens to positions in the second and third phase of the game.
    public void gatherMoveCandidates(Game g, Position selectedPos) {
        candidates.clear();

        int remaining = 9 - g.getBlackOut();
        if (g.isWhiteRound()) {
            remaining = 9 - g.getWhiteOut();
        }
        assert remaining >= 3;

        if (remaining > 3) {
            var neighList = g.edges.get(selectedPos);
            for (var n : neighList) {
                if (n.getStatus() == PositionStatus.EMPTY) {
                    candidates.add(n);
                }
            }
        } else {
            var neighList = g.positions;
            for (var n : neighList) {
                if (n.getStatus() == PositionStatus.EMPTY) {
                    candidates.add(n);
                }
            }
        }
    }


    public Set<Position> getCandidates() {
        return candidates;
    }

    public void setCandidates(Set<Position> candidates) {
        this.candidates = new HashSet<>(candidates);
    }
}
