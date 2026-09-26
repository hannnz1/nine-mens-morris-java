package io.github.hannnz1.morris.engine.ai;

import io.github.hannnz1.morris.engine.*;
import java.time.Duration;
import java.util.*;

/** Stateless search: all deadline, random and best-move state belongs to one invocation. */
public final class AlphaBetaMorrisAi implements MorrisAi {
    private static final int INF = 100_000;
    private final PositionEvaluator evaluator = new PositionEvaluator();
    @Override public GameAction chooseAction(GameState state, Difficulty difficulty, long seed, Duration budget) {
        return chooseDetailed(state, difficulty, seed, budget).action();
    }
    public AiDecision chooseDetailed(GameState state, Difficulty difficulty, long seed, Duration budget) {
        Objects.requireNonNull(difficulty); Objects.requireNonNull(budget);
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Budget must be positive");
        long started = System.nanoTime();
        var actions = new ArrayList<>(LegalActions.of(state));
        if (actions.isEmpty()) throw new IllegalArgumentException("No legal action in this state");
        var random = new Random(seed);
        Collections.shuffle(actions, random);
        GameAction best = actions.get(0);
        if (difficulty == Difficulty.EASY && random.nextInt(10) < 3) return new AiDecision(best, false, 0);
        var search = new Search(difficulty == Difficulty.HARD ? started + Math.min(budget.toNanos(), Duration.ofSeconds(60).toNanos()) : Long.MAX_VALUE);
        int completed = 0;
        int target = difficulty == Difficulty.EASY ? 1 : difficulty == Difficulty.MEDIUM ? 3 : 8;
        int start = difficulty == Difficulty.HARD ? 1 : target;
        try {
            actions.sort(Comparator.comparingInt((GameAction a) -> PositionEvaluator.order(state, a)).reversed());
            for (int depth = start; depth <= target; depth++) {
                GameAction iterationBest = best; int alpha = -INF;
                for (var action : actions) {
                    search.check();
                    var next = GameEngine.restore(state).apply(action);
                    int score = next.currentPlayer() == state.currentPlayer()
                            ? search.value(next, depth, alpha, INF, 1)
                            : -search.value(next, depth - 1, -INF, -alpha, 1);
                    if (score > alpha) { alpha = score; iterationBest = action; }
                }
                best = iterationBest; completed = depth;
                actions.remove(best); actions.add(0, best);
                if (alpha >= 9900) break;
            }
        } catch (SearchExpired ignored) { /* Only complete iterations replace best. */ }
        try { GameEngine.restore(state).apply(best); }
        catch (GameRuleException invalid) { return new AiDecision(new RandomMover(seed).chooseAction(state), true, completed); }
        return new AiDecision(best, false, completed);
    }
    private final class Search {
        final long deadline;
        Search(long deadline) { this.deadline = deadline; }
        void check() { if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw SearchExpired.INSTANCE; }
        int value(GameState s, int depth, int alpha, int beta, int ply) {
            check();
            if (s.winner() != null) return s.winner() == s.currentPlayer() ? 10000 - ply : -10000 + ply;
            if (s.drawReason() != null) return 0;
            if (depth <= 0 && !s.removalPending()) return evaluator.evaluate(s, s.currentPlayer());
            var actions = new ArrayList<>(LegalActions.of(s));
            if (actions.isEmpty()) return -10000 + ply;
            actions.sort(Comparator.comparingInt((GameAction a) -> PositionEvaluator.order(s, a)).reversed());
            int best = -INF;
            for (var action : actions) {
                check();
                var next = GameEngine.restore(s).apply(action);
                int score = next.currentPlayer() == s.currentPlayer()
                        ? value(next, depth, alpha, beta, ply + 1)
                        : -value(next, depth - 1, -beta, -alpha, ply + 1);
                best = Math.max(best, score); alpha = Math.max(alpha, score);
                if (alpha >= beta) break;
            }
            return best;
        }
    }
    private static final class SearchExpired extends RuntimeException {
        static final SearchExpired INSTANCE = new SearchExpired();
        private SearchExpired() { super(null, null, false, false); }
    }
}
