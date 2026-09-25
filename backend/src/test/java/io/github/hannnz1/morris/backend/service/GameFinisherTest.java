package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.GameApiDtos.CreateGameRequest;
import io.github.hannnz1.morris.backend.persistence.GameSessionEntity;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.support.MutableClock;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// GameFinisher.finish's contract (see its Javadoc) requires the caller to already hold the
// row lock (via findByIdForUpdate) and be inside the transaction that will commit the result;
// it does not open or commit a transaction itself. findByIdForUpdate's PESSIMISTIC_WRITE query
// therefore requires an active transaction around each test body - provided here with the same
// TransactionTemplate pattern GamePostgresConcurrencyTest uses, rather than test-method
// @Transactional (which would roll back and never let the follow-up findById see the result).
class GameFinisherTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock testClock() {
            return MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired
    private GameFinisher finisher;
    @Autowired
    private GameSessionService gameSessions;
    @Autowired
    private GameSessionRepository games;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void finishSetsTerminalStatusResultAndFinishedAt() {
        var created = gameSessions.create(new CreateGameRequest("Han"));

        transaction.executeWithoutResult(tx -> {
            GameSessionEntity entity = games.findByIdForUpdate(created.game().id()).orElseThrow();
            finisher.finish(entity, Player.WHITE, "RESIGN");
        });

        GameSessionEntity reloaded = games.findById(created.game().id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("WHITE_WON");
        assertThat(reloaded.getResultWinner()).isEqualTo("WHITE");
        assertThat(reloaded.getResultReason()).isEqualTo("RESIGN");
        assertThat(reloaded.getFinishedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void finishWithNoWinnerRecordsADrawnStatus() {
        var created = gameSessions.create(new CreateGameRequest("Han"));

        transaction.executeWithoutResult(tx -> {
            GameSessionEntity entity = games.findByIdForUpdate(created.game().id()).orElseThrow();
            finisher.finish(entity, null, "DRAW_AGREED");
        });

        GameSessionEntity reloaded = games.findById(created.game().id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DRAWN");
        assertThat(reloaded.getResultWinner()).isNull();
        assertThat(reloaded.getResultReason()).isEqualTo("DRAW_AGREED");
    }

    @Test
    void abortedHasItsOwnStatusDistinctFromDrawn() {
        var created = gameSessions.create(new CreateGameRequest("Han"));

        transaction.executeWithoutResult(tx -> {
            GameSessionEntity entity = games.findByIdForUpdate(created.game().id()).orElseThrow();
            finisher.finish(entity, null, "ABORTED");
        });

        assertThat(games.findById(created.game().id()).orElseThrow().getStatus()).isEqualTo("ABORTED");
    }
}
