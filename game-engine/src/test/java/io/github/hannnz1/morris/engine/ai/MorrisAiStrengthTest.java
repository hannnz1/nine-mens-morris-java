package io.github.hannnz1.morris.engine.ai;
import io.github.hannnz1.morris.engine.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class MorrisAiStrengthTest {
    @Test @Tag("strength") void mediumWinsAtLeast65Of100() { match(Difficulty.MEDIUM,100,65); }
    @Test @Tag("strength") void hardWinsAtLeast80Of100() { match(Difficulty.HARD,100,80); }
    @Test void mediumStrengthSmoke() { match(Difficulty.MEDIUM,8,4); }
    private void match(Difficulty stronger,int count,int threshold) {
        var ai=new AlphaBetaMorrisAi();int wins=0,losses=0,draws=0;
        long budget=Long.getLong("morris.ai.strength.hardBudgetMs",800L);
        for(int game=0;game<count;game++) {
            var engine=GameEngine.newGame();var side=game%2==0?Player.WHITE:Player.BLACK;
            int actions=0;
            while(engine.state().phase()!=GamePhase.GAME_OVER&&actions<1500) {
                var state=engine.state();var d=state.currentPlayer()==side?stronger:Difficulty.EASY;
                var decision=ai.chooseDetailed(state,d,982451653L+game*10000L+actions,Duration.ofMillis(budget));
                assertThat(decision.fallbackUsed()).isFalse();
                engine.apply(decision.action());actions++;
            }
            assertThat(engine.state().phase()).isEqualTo(GamePhase.GAME_OVER);
            if(engine.state().winner()==side)wins++;else if(engine.state().winner()==null)draws++;else losses++;
            System.out.printf("STRENGTH %s game=%d/%d wins=%d losses=%d draws=%d budget=%dms%n",stronger,game+1,count,wins,losses,draws,budget);
        }
        assertThat(wins).as("%s vs EASY wins out of %d; losses=%d draws=%d",stronger,count,losses,draws).isGreaterThanOrEqualTo(threshold);
    }
    @Test void hardMoveBudgetsCoverPlacingMovingFlyingAndRemoval() {
        var ai=new AlphaBetaMorrisAi();
        var states=java.util.List.of(GameEngine.newGame().state(),
                MorrisAiTest.position("A1 D1 B2 B6 F4", "C3 D3 E3 E5 G7",0,false),
                MorrisAiTest.position("A1 D1 B2", "C3 D3 E3 F6",0,false),
                MorrisAiTest.position("A1 D1 G1 B2", "C3 D3 E3 F6",0,true));
        for(var state:states){
            ai.chooseAction(state,Difficulty.HARD,3,Duration.ofMillis(5));
            long start=System.nanoTime();
            var move=ai.chooseAction(state,Difficulty.HARD,3,Duration.ofMillis(800));
            assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThanOrEqualTo(900);
            assertThatCode(()->GameEngine.restore(state).apply(move)).doesNotThrowAnyException();
        }
    }
}
