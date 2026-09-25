package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.engine.GameResult;
import io.github.hannnz1.morris.engine.GameState;

/**
 * Decides how a game ends when the side to move has run out of time, per spec M2.3's first-move
 * grace period: "双方各自的第一步只有 30 秒（不消耗主时间）...超时扫描发现是第一步超时，就以 ABORTED
 * 结束而不是 TIMEOUT". A side's move is its first move exactly when that side's
 * {@code piecesToPlace} is still 9 in the state as it stood immediately before the move - every
 * game opens with placements and a first move can never form a mill, so no extra bookkeeping is
 * needed to detect it.
 *
 * <p>This is the single place that decision is made. {@link GameSessionService#performAction} calls
 * it on-arrival (a move that shows up after the deadline already passed); Task 6's background
 * timeout scanner calls the same method for a side that never moves at all - so the two paths can
 * never disagree about whether a given expiry is a TIMEOUT loss or an ABORTED wash.
 */
final class ClockTimeoutOutcome {

    private ClockTimeoutOutcome() {
    }

    /**
     * @param stateBeforeMove the game state as it stood before the side-to-move's (missing or late)
     *                        action - {@code stateBeforeMove.currentPlayer()} is the side whose
     *                        clock ran out
     * @return {@code (null, "ABORTED")} if it was that side's first move, otherwise
     *         {@code (opponent, "TIMEOUT")}
     */
    static GameResult forExpiry(GameState stateBeforeMove) {
        var mover = stateBeforeMove.currentPlayer();
        if (stateBeforeMove.piecesToPlace(mover) == 9) {
            return new GameResult(null, "ABORTED");
        }
        return new GameResult(mover.opponent(), "TIMEOUT");
    }
}
