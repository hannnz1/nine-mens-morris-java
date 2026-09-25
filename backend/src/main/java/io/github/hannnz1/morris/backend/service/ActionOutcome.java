package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;

/**
 * {@code performAction}'s result, distinguishing an ordinary (possibly game-ending) move from a
 * move that was rejected because the mover's own clock had already reached zero. Per spec M2.3,
 * the service method returns this result object rather than throwing inside the transaction - the
 * write (via {@code GameFinisher.finish}) is committed either way, and it's {@code GameController}
 * that turns {@code rejectedByTimeout} into an HTTP 409 after the transaction has already committed.
 *
 * @param game              the current (or just-finished) game state - always populated
 * @param rejectedByTimeout true only when the action itself was never applied because the mover's
 *                          deadline had already passed; false for every other outcome, including a
 *                          move that itself ends the game by mill, no-moves, or draw
 */
public record ActionOutcome(GameResponse game, boolean rejectedByTimeout) {
}
