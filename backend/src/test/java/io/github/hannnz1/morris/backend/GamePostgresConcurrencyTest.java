package io.github.hannnz1.morris.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordRepository;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real PostgreSQL locks, separate HTTP requests/transactions, no mocked repositories. */
@AutoConfigureMockMvc
class GamePostgresConcurrencyTest extends PostgresIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GameSessionRepository games;
    @Autowired private IdempotencyRecordRepository records;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService workers;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        records.deleteAll();
        games.deleteAll();
        workers = Executors.newFixedThreadPool(2);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void initializedSchemaEnforcesForeignKeysUniquenessAndCascadeDeletion() throws Exception {
        Created game = create(true);
        assertThat(status(action(game, "constraint-key", "A1", 1))).isEqualTo(200);

        assertThatThrownBy(() -> jdbc.update("""
                insert into idempotency_records
                    (id, game_id, idempotency_key, request_fingerprint, response_json, created_at)
                select ?, game_id, idempotency_key, request_fingerprint, response_json, created_at
                from idempotency_records where game_id = ?
                """, UUID.randomUUID(), game.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                insert into idempotency_records
                    (id, game_id, idempotency_key, request_fingerprint, response_json, created_at)
                values (?, ?, 'orphan', 'fingerprint', '{}', current_timestamp)
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        games.deleteById(game.id());
        assertThat(records.count()).isZero();
    }

    @Test
    void concurrentJoinRetriesWithTheSameProofRecoverOneSeat() throws Exception {
        Created game = create(false);
        String proof = new io.github.hannnz1.morris.backend.service.TokenService().generate();
        Callable<MvcResult> retry = () -> mvc.perform(post("/api/v1/games/{id}/join", game.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("blackPlayer", "Bob", "joinToken", proof)))).andReturn();
        List<MvcResult> results = race(game.id(), retry, retry);
        assertThat(results).extracting(this::status).containsExactly(200, 200);
        assertThat(json(results.get(0)).get("credential")).isEqualTo(json(results.get(1)).get("credential"));
        assertThat(json(results.get(0)).at("/game/state")).isEqualTo(json(results.get(1)).at("/game/state"));
        assertThat(games.findById(game.id()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentJoinReturnsOneSeatAndOneGameAlreadyFull() throws Exception {
        Created game = create(false);
        List<MvcResult> results = race(game.id(), () -> join(game.id(), "Bob"),
                () -> join(game.id(), "Carol"));

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult rejected = results.stream().filter(result -> status(result) == 409).findFirst().orElseThrow();
        MvcResult accepted = results.stream().filter(result -> status(result) == 200).findFirst().orElseThrow();
        assertThat(json(rejected).get("code").asText()).isEqualTo("GAME_ALREADY_FULL");
        var saved = games.findById(game.id()).orElseThrow();
        assertThat(saved.getBlackPlayer()).isEqualTo(json(accepted).at("/credential/playerName").asText());
        assertThat(saved.getVersion()).isEqualTo(1);
    }

    @Test
    void simultaneousIdenticalRetriesReturnExactlyTheSameSavedResponse() throws Exception {
        Created game = create(true);
        List<MvcResult> results = race(game.id(), () -> action(game, "same-key", "A1", 1),
                () -> action(game, "same-key", "A1", 1));

        assertThat(results).extracting(this::status).containsExactly(200, 200);
        assertThat(json(results.get(0))).isEqualTo(json(results.get(1)));
        assertThat(games.findById(game.id()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentActionsAtTheSameVersionApplyOnlyOneMove() throws Exception {
        Created game = create(true);
        List<MvcResult> results = race(game.id(), () -> action(game, "first", "A1", 1),
                () -> action(game, "second", "D1", 1));

        assertThat(results).extracting(this::status).containsExactlyInAnyOrder(200, 409);
        MvcResult rejected = results.stream().filter(result -> status(result) == 409).findFirst().orElseThrow();
        assertThat(json(rejected).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(json(read(game.id())).at("/state/whitePiecesToPlace").asInt()).isEqualTo(8);
        assertThat(games.findById(game.id()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void concurrentReuseOfAKeyWithDifferentPayloadIsRejected() throws Exception {
        Created game = create(true);
        List<MvcResult> results = race(game.id(), () -> action(game, "shared", "A1", 1),
                () -> action(game, "shared", "D1", 1));

        assertThat(results).extracting(this::status).containsExactlyInAnyOrder(200, 409);
        MvcResult rejected = results.stream().filter(result -> status(result) == 409).findFirst().orElseThrow();
        assertThat(json(rejected).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void rowLockDoesNotBlockPublicReadOrAnotherGamesWrite() throws Exception {
        Created locked = create(true);
        Created other = create(true);
        transaction.executeWithoutResult(tx -> {
            games.findByIdForUpdate(locked.id()).orElseThrow();
            MvcResult read = result(workers.submit(() -> read(locked.id())), 2);
            MvcResult write = result(workers.submit(() -> action(other, "other", "A1", 1)), 2);
            assertThat(status(read)).isEqualTo(200);
            assertThat(status(write)).isEqualTo(200);
        });
        assertThat(games.findById(locked.id()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void rollbackReleasesTheLockAndRollsBackBothMoveAndIdempotencyRecord() throws Exception {
        Created game = create(true);
        Future<MvcResult> waiting = transaction.execute(tx -> {
            // HTTP action joins this outer transaction; hold its changes uncommitted.
            try {
                assertThat(status(action(game, "rollback-key", "A1", 1))).isEqualTo(200);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            Future<MvcResult> retry = workers.submit(() -> action(game, "rollback-key", "A1", 1));
            awaitBlockedWriters(1);
            tx.setRollbackOnly();
            return retry;
        });
        MvcResult response = result(waiting, 8);
        assertThat(status(response)).isEqualTo(200);
        assertThat(json(response).get("version").asLong()).isEqualTo(2);
        assertThat(games.findById(game.id()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void lockTimeoutReturnsGameBusyAndTheSameRequestCanSucceedAfterRelease() throws Exception {
        Created game = create(true);
        transaction.executeWithoutResult(tx -> {
            games.findByIdForUpdate(game.id()).orElseThrow();
            MvcResult response = result(workers.submit(() -> action(game, "timeout-key", "A1", 1)), 8);
            assertThat(status(response)).isEqualTo(503);
            try {
                assertThat(json(response).get("code").asText()).isEqualTo("GAME_BUSY");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            assertThat(records.count()).isZero();
        });
        assertThat(games.findById(game.id()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(status(action(game, "timeout-key", "A1", 1))).isEqualTo(200);
        assertThat(records.count()).isEqualTo(1);
    }

    private List<MvcResult> race(UUID id, Callable<MvcResult> first, Callable<MvcResult> second) {
        List<Future<MvcResult>> requests = transaction.execute(tx -> {
            games.findByIdForUpdate(id).orElseThrow();
            List<Future<MvcResult>> pending = List.of(workers.submit(first), workers.submit(second));
            // Prove both requests reached the database and are waiting on this row.
            // A simultaneous thread start alone would not establish an actual lock race.
            awaitBlockedWriters(2);
            return pending;
        });
        return requests.stream().map(request -> result(request, 8)).toList();
    }

    private void awaitBlockedWriters(int count) {
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        select count(*) from pg_stat_activity
                        where datname = current_database()
                          and cardinality(pg_blocking_pids(pid)) > 0
                        """, Integer.class)).isGreaterThanOrEqualTo(count));
    }

    private MvcResult result(Future<MvcResult> future, int seconds) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent request did not finish as expected", exception);
        }
    }

    private Created create(boolean withBlack) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                .content("{\"whitePlayer\":\"Alice\"}")).andReturn();
        assertThat(status(result)).isEqualTo(201);
        JsonNode body = json(result);
        UUID id = UUID.fromString(body.at("/game/id").asText());
        if (withBlack) assertThat(status(join(id, "Bob"))).isEqualTo(200);
        return new Created(id,
                body.at("/whiteCredential/token").asText());
    }

    private MvcResult join(UUID id, String name) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/join", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("blackPlayer", name, "joinToken", new io.github.hannnz1.morris.backend.service.TokenService().generate())))).andReturn();
    }

    private MvcResult action(Created game, String key, String to, long version) throws Exception {
        return mvc.perform(post("/api/v1/games/{id}/actions", game.id())
                .header("X-Player-Token", game.token()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of(
                        "type", "PLACE", "to", to, "expectedVersion", version)))).andReturn();
    }

    private MvcResult read(UUID id) throws Exception {
        return mvc.perform(get("/api/v1/games/{id}", id)).andReturn();
    }

    private int status(MvcResult result) { return result.getResponse().getStatus(); }
    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private record Created(UUID id, String token) { }
}
