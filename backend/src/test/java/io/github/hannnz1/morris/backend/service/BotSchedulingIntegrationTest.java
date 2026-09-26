package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.ai.Difficulty;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
@SpringBootTest(properties={"morris.scheduling.enabled=true","morris.bot.think-delay-min-ms=10","morris.bot.think-delay-max-ms=20"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class BotSchedulingIntegrationTest extends PostgresIntegrationTest {
    @Autowired GameSessionService games;
    @Autowired PlayerService players;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired BotWatchdog watchdog;
    @Autowired PlatformTransactionManager transactions;
    final List<UUID> ids=new ArrayList<>();
    @AfterEach void cleanup(){ for(var id:ids) jdbc.update("update game_sessions set status='CANCELLED' where id=?",id); }
    UUID create(){
        String token=tokens.generate();players.createOrGet(new CreatePlayerRequest("AutoTest",token));
        UUID id=games.createBotGame(players.requirePlayer(token),UUID.randomUUID().toString(),new BotGameRequest(Difficulty.EASY,PlayerColor.BLACK,"5+3")).game().id();
        ids.add(id);return id;
    }
    @Test void productionCommitEventAutomaticallyStartsComputerWhite() {
        UUID id=create();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(games.get(id).version()).isEqualTo(1));
    }
    @Test void rolledBackCreateDoesNotExistAndCannotBePlayed() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{create();status.setRollbackOnly();});
        UUID id=ids.get(0);
        assertThat(jdbc.queryForObject("select count(*) from game_sessions where id=?",Integer.class,id)).isZero();
    }
    @Test void watchdogRestoresStalledComputerTurnWithoutCommitEvent() {
        UUID id=create();
        await().atMost(Duration.ofSeconds(2)).until(()->games.get(id).version()==1);
        // Restore the persisted initial state directly, simulating a restart with a lost queued task.
        jdbc.update("update game_sessions set state_json=?, turn_started_at=CURRENT_TIMESTAMP - interval '4 seconds', turn_deadline_at=CURRENT_TIMESTAMP + interval '30 seconds' where id=?",
                stateJson(),id);
        watchdog.scanOnce();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(games.get(id).version()).isEqualTo(2));
    }
    String stateJson(){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(io.github.hannnz1.morris.engine.GameEngine.newGame().state());}catch(Exception e){throw new RuntimeException(e);}}
}
