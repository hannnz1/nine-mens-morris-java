package io.github.hannnz1.morris.engine;

/** The terminal outcome of a finished game, as game-engine itself can determine it. */
public record GameResult(Player winner, String reason) {
}
