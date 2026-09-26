package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import java.time.Duration;
public interface MorrisAi {
    GameAction chooseAction(GameState state, Difficulty difficulty, long seed, Duration budget);
}
