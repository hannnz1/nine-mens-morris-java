package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.PlayerEntity;
import io.github.hannnz1.morris.backend.persistence.PlayerRepository;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec M2.9: "「双方同时提和」「双方同时再来一局」各 100 次循环". Each iteration uses a fresh pair of
 * players and a fresh game (so neither the per-player 20/min create limit nor the 5-active-games
 * cap is ever approached), and fires both players' OFFER on two real threads released together by
 * a CountDownLatch. The row lock serializes them; whichever lands second must see the first offer
 * and turn into an accept, so exactly one draw / exactly one rematch game results every time.
 */
class SimultaneousOffersConcurrencyTest extends PostgresIntegrationTest {

    private static final int ITERATIONS = 100;
    private static final List<String> ALL_STATUSES =
            List.of("WAITING_FOR_PLAYER", "IN_PROGRESS", "WHITE_WON", "BLACK_WON", "DRAWN", "ABORTED", "CANCELLED");

    @Autowired private GameSessionService gameSessions;
    @Autowired private GameSessionRepository games;
    @Autowired private PlayerService playerService;
    @Autowired private PlayerRepository players;
    @Autowired private TokenService tokens;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void simultaneousDrawOffersEndInExactlyOneAgreedDrawEveryTime() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Pair pair = freshPair(iteration);
            GameResponse started = startGame(pair);
            long versionBefore = games.findById(started.id()).orElseThrow().getVersion();

            List<GameResponse> responses = race(
                    () -> gameSessions.offerDraw(started.id(), pair.whiteToken(), "draw-w-" + UUID.randomUUID(), "OFFER"),
                    () -> gameSessions.offerDraw(started.id(), pair.blackToken(), "draw-b-" + UUID.randomUUID(), "OFFER"));

            String at = "iteration " + iteration;
            assertThat(responses).as(at).filteredOn(r -> "DRAWN".equals(r.status())).hasSize(1);
            GameResponse offered = responses.stream().filter(r -> !"DRAWN".equals(r.status())).findFirst().orElseThrow();
            GameResponse drawn = responses.stream().filter(r -> "DRAWN".equals(r.status())).findFirst().orElseThrow();
            // The first to take the lock only recorded its offer; the second saw it and accepted.
            assertThat(offered.status()).as(at).isEqualTo("IN_PROGRESS");
            assertThat(offered.drawOfferedBy()).as(at).isIn("WHITE", "BLACK");
            assertThat(drawn.result().reason()).as(at).isEqualTo("DRAW_AGREED");
            assertThat(drawn.result().winner()).as(at).isNull();
            assertThat(drawn.drawOfferedBy()).as(at).isNull();

            GameSessionEntity stored = games.findById(started.id()).orElseThrow();
            assertThat(stored.getStatus()).as(at).isEqualTo("DRAWN");
            assertThat(stored.getResultReason()).as(at).isEqualTo("DRAW_AGREED");
            assertThat(stored.getResultWinner()).as(at).isNull();
            assertThat(stored.getDrawOfferedBy()).as(at).isNull();
            assertThat(stored.getFinishedAt()).as(at).isNotNull();
            // Exactly one offer write (+1) and exactly one finish write (+1). A double finish, or two
            // recorded offers, would show up here as a larger delta.
            assertThat(stored.getVersion() - versionBefore).as(at).isEqualTo(2L);
            assertThat(stored.getWhiteDrawOffers() + stored.getBlackDrawOffers()).as(at).isEqualTo(1);
            assertThat(stored.getVersion()).as(at).isEqualTo(drawn.version());
        }
    }

    @Test
    void simultaneousRematchOffersCreateExactlyOneNewGameEveryTime() throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Pair pair = freshPair(iteration);
            GameResponse started = startGame(pair);
            GameResponse finished = gameSessions.resign(started.id(), pair.whiteToken(), "resign-" + UUID.randomUUID());
            assertThat(finished.status()).isEqualTo("BLACK_WON");

            List<GameResponse> responses = race(
                    () -> gameSessions.offerRematch(started.id(), pair.whiteToken(), "rematch-w-" + UUID.randomUUID(), "OFFER"),
                    () -> gameSessions.offerRematch(started.id(), pair.blackToken(), "rematch-b-" + UUID.randomUUID(), "OFFER"));

            String at = "iteration " + iteration;
            // Exactly one new game exists for this pair: the original plus the rematch.
            assertThat(games.countActiveGamesForPlayer(pair.white().getId(), ALL_STATUSES)).as(at).isEqualTo(2);
            assertThat(games.countActiveGamesForPlayer(pair.black().getId(), ALL_STATUSES)).as(at).isEqualTo(2);

            // The second request to take the lock created the game; the first only recorded its offer.
            assertThat(responses).as(at).filteredOn(r -> r.rematchGameId() != null).hasSize(1);
            GameResponse offered = responses.stream().filter(r -> r.rematchGameId() == null).findFirst().orElseThrow();
            UUID rematchGameId = responses.stream().map(GameResponse::rematchGameId)
                    .filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(offered.rematchOfferedBy()).as(at).isIn("WHITE", "BLACK");

            GameSessionEntity original = games.findById(started.id()).orElseThrow();
            assertThat(original.getRematchGameId()).as(at).isEqualTo(rematchGameId);

            // Both players agree on the same rematch game: any later call from either side replays
            // the stored rematchGameId instead of creating another game.
            GameResponse whiteView = gameSessions.offerRematch(started.id(), pair.whiteToken(), "again-w-" + UUID.randomUUID(), "OFFER");
            GameResponse blackView = gameSessions.offerRematch(started.id(), pair.blackToken(), "again-b-" + UUID.randomUUID(), "OFFER");
            assertThat(whiteView.rematchGameId()).as(at).isEqualTo(rematchGameId);
            assertThat(blackView.rematchGameId()).as(at).isEqualTo(rematchGameId);
            assertThat(games.countActiveGamesForPlayer(pair.white().getId(), ALL_STATUSES)).as(at).isEqualTo(2);

            GameSessionEntity rematch = games.findById(rematchGameId).orElseThrow();
            assertThat(rematch.getStatus()).as(at).isEqualTo("IN_PROGRESS");
            assertThat(rematch.getWhitePlayerId()).as(at).isEqualTo(pair.black().getId()); // colours swapped
            assertThat(rematch.getBlackPlayerId()).as(at).isEqualTo(pair.white().getId());
        }
    }

    private List<GameResponse> race(Callable<GameResponse> first, Callable<GameResponse> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<GameResponse> a = pool.submit(() -> {
            ready.countDown();
            go.await();
            return first.call();
        });
        Future<GameResponse> b = pool.submit(() -> {
            ready.countDown();
            go.await();
            return second.call();
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown(); // release both threads at the same moment
        // Any exception (lock timeout, NO_PENDING_OFFER, ...) surfaces here and fails the test.
        return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }

    private GameResponse startGame(Pair pair) {
        var created = gameSessions.createForPlayer(pair.white(), "create-" + UUID.randomUUID(), "5+3");
        return gameSessions.joinByBearer(created.game().id(), pair.black(), "join-" + UUID.randomUUID()).game();
    }

    // Players go through PlayerService.createOrGet, never POST /api/v1/players (that endpoint's
    // per-IP limit is shared by every HTTP-level test class in the suite).
    private Pair freshPair(int iteration) {
        String whiteToken = tokens.generate();
        String blackToken = tokens.generate();
        playerService.createOrGet(new CreatePlayerRequest("W" + iteration, whiteToken));
        playerService.createOrGet(new CreatePlayerRequest("B" + iteration, blackToken));
        PlayerEntity white = players.findByTokenHash(tokens.hash(whiteToken)).orElseThrow();
        PlayerEntity black = players.findByTokenHash(tokens.hash(blackToken)).orElseThrow();
        return new Pair(white, whiteToken, black, blackToken);
    }

    private record Pair(PlayerEntity white, String whiteToken, PlayerEntity black, String blackToken) {
    }
}
