package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import java.util.*;
public final class LegalActions {
    private LegalActions() {}
    public static List<GameAction> of(GameState state) {
        var engine = GameEngine.restore(state);
        var actions = new ArrayList<GameAction>();
        for (var p : engine.legalPlacements()) actions.add(GameAction.place(p));
        engine.legalMoves().forEach((from, targets) -> targets.forEach(to -> actions.add(GameAction.move(from, to))));
        for (var p : engine.removablePieces()) actions.add(GameAction.remove(p));
        return List.copyOf(actions);
    }
}
