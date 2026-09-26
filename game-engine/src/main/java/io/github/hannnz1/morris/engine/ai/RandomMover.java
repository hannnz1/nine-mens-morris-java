package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import java.util.Random;
public final class RandomMover {
    private final Random random;
    public RandomMover(long seed) { random = new Random(seed); }
    public GameAction chooseAction(GameState state) {
        var actions = LegalActions.of(state);
        if (actions.isEmpty()) throw new IllegalArgumentException("No legal action in this state");
        return actions.get(random.nextInt(actions.size()));
    }
}
