package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class MorrisAiLegalityTest {
    @Test void completeSeededGamesNeverRequireSafetyFallback() {
        var ai = new AlphaBetaMorrisAi();
        for (var difficulty : Difficulty.values()) {
            int games = difficulty == Difficulty.HARD ? 50 : 1000;
            for (int game = 0; game < games; game++) {
                var engine = GameEngine.newGame();
                var bot = game % 2 == 0 ? Player.WHITE : Player.BLACK;
                var random = new RandomMover(game + 1900);
                int actions = 0;
                while (engine.state().phase() != GamePhase.GAME_OVER && actions < 1500) {
                    var s = engine.state();
                    if (s.currentPlayer() == bot) {
                        var choice = ai.chooseDetailed(s, difficulty, game * 10000L + actions, Duration.ofMillis(20));
                        assertThat(choice.fallbackUsed()).as("%s game %s action %s", difficulty, game, actions).isFalse();
                        engine.apply(choice.action());
                    } else engine.apply(random.chooseAction(s));
                    actions++;
                }
                assertThat(engine.state().phase()).as("%s game %s", difficulty, game).isEqualTo(GamePhase.GAME_OVER);
            }
            System.out.printf("LEGALITY %s: %d complete games, no fallback%n", difficulty, games);
        }
    }
}
