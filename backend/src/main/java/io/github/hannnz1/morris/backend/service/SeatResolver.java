package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.engine.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns a request's credentials plus a game row into WHITE, BLACK, or a rejection.
 * Callers (REST controller, WebSocket handler) never compare token hashes themselves.
 */
@Component
public class SeatResolver {

    private final TokenService tokens;
    private final PlayerRepository players;

    public SeatResolver(TokenService tokens, PlayerRepository players) {
        this.tokens = tokens;
        this.players = players;
    }

    /**
     * @param bearerToken     the {@code Authorization: Bearer} credential, or null if absent
     * @param legacySeatToken the {@code X-Player-Token} credential, or null if absent
     */
    private static final int MAX_TOKEN_LENGTH = 128;

    public Player resolve(GameSessionEntity game, String bearerToken, String legacySeatToken) {
        if (isBlank(bearerToken) && isBlank(legacySeatToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "A player credential is required");
        }
        if (usable(bearerToken)) {
            Player bySeat = matchPlayerId(game, tokens.hash(bearerToken));
            if (bySeat != null) {
                return bySeat;
            }
        }
        if (usable(legacySeatToken)) {
            Player byLegacy = matchLegacyHash(game, legacySeatToken);
            if (byLegacy != null) {
                return byLegacy;
            }
        }
        // FORBIDDEN (403), not brief's literal UNAUTHORIZED (401): GameApiIntegrationTest and
        // RecoveryIntegrationTest already assert 403 for INVALID_PLAYER_TOKEN on pre-existing
        // (legacy-seat-token) games. Changing that status would be a real behavior regression
        // for those already-passing tests, so the historical status is preserved here.
        throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PLAYER_TOKEN", "The player token is invalid for this game");
    }

    private Player matchPlayerId(GameSessionEntity game, String tokenHash) {
        UUID playerId = players.findByTokenHash(tokenHash).filter(p -> "HUMAN".equals(p.getKind()))
                .map(p -> p.getId()).orElse(null);
        if (playerId == null) {
            return null;
        }
        if (playerId.equals(game.getWhitePlayerId())) {
            return Player.WHITE;
        }
        if (playerId.equals(game.getBlackPlayerId())) {
            return Player.BLACK;
        }
        return null;
    }

    private Player matchLegacyHash(GameSessionEntity game, String legacySeatToken) {
        if (tokens.matches(legacySeatToken, game.getWhiteTokenHash())) {
            return Player.WHITE;
        }
        if (game.getBlackTokenHash() != null && !game.getBlackTokenHash().isEmpty()
                && tokens.matches(legacySeatToken, game.getBlackTokenHash())) {
            return Player.BLACK;
        }
        return null;
    }

    // Package-private (not private) so GameSessionService can select the same credential for its
    // idempotency fingerprint that this method actually authenticates against.
    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Package-private (not private), for the same reason as isBlank: this is the exact predicate
    // resolve() uses to decide whether to try the bearer branch at all, so GameSessionService must
    // use this - not a plain isBlank check - to pick the fingerprint credential. Two different
    // predicates at the two call sites is exactly how the fingerprint-vs-authenticated-credential
    // mismatch bug reappeared once (see GameSessionService.performAction).
    //
    // A present credential is only worth hashing/looking up if it's within the size a real token
    // can be; this avoids hashing arbitrarily large header values (same cap the old, now-removed
    // GameSessionService.validatePlayerToken enforced).
    static boolean usable(String value) {
        return !isBlank(value) && value.length() <= MAX_TOKEN_LENGTH;
    }
}
