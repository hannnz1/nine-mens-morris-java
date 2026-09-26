package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.*;
import io.github.hannnz1.morris.engine.ai.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class BotTurnSchedulerTest extends PostgresIntegrationTest {
    @Autowired GameSessionService games;
    @Autowired BotGameAccess access;
    @Autowired PlayerService players;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final List<UUID> ids=new ArrayList<>();
    String token;
    UUID create(PlayerColor color) {
        token=tokens.generate(); players.createOrGet(new CreatePlayerRequest("BotTest",token));
        UUID id=games.createBotGame(players.requirePlayer(token),UUID.randomUUID().toString(),new BotGameRequest(Difficulty.EASY,color,"5+3")).game().id();
        ids.add(id); return id;
    }
    @AfterEach void cleanup(){ for(var id:ids) jdbc.update("update game_sessions set status='CANCELLED' where id=?",id); }
    BotTurnScheduler scheduler(MorrisAi ai, java.util.concurrent.Executor executor) {
        return new BotTurnScheduler(access,ai,(task,delay)->task.run(),executor,()->true,Duration.ofMillis(20),0,0);
    }
    @Test void deduplicatesQueuedTasksAndResignationCancelsTheirWrite() {
        UUID id=create(PlayerColor.BLACK);
        var queued=new ArrayList<Runnable>();
        var s=scheduler(new AlphaBetaMorrisAi(),queued::add);
        s.onGameCommitted(games.get(id)); s.onGameCommitted(games.get(id));
        assertThat(queued).hasSize(1);
        games.resign(id,token,"resign"); queued.remove(0).run();
        assertThat(games.get(id).state().piecesOnBoard(Player.WHITE)).isZero();
        assertThat(s.isInFlight(id)).isFalse();
    }
    @Test void firstBotMoveAndHumanReplyBothAdvanceTheBoard() {
        UUID id=create(PlayerColor.BLACK); var s=scheduler(new AlphaBetaMorrisAi(),Runnable::run);
        s.onGameCommitted(games.get(id));
        var first=games.get(id); assertThat(first.version()).isEqualTo(1);
        var move=first.legalPlacements().get(0);
        games.performAction(id,token,null,"human",new ActionRequest(ActionType.PLACE,null,move,first.version()));
        s.onGameCommitted(games.get(id));
        assertThat(games.get(id).version()).isEqualTo(3);
        assertThat(games.get(id).state().currentPlayer()).isEqualTo(Player.BLACK);
    }
    @Test void threeFailuresCauseOneRandomRecoveryAndThenSearchResumes() {
        UUID id=create(PlayerColor.BLACK); var calls=new AtomicInteger();
        var s=scheduler((state,d,seed,b)->{calls.incrementAndGet();throw new IllegalStateException("injected search failure");},Runnable::run);
        for(int i=0;i<4;i++) s.onGameCommitted(games.get(id));
        assertThat(calls.get()).isEqualTo(3);
        assertThat(games.get(id).version()).isEqualTo(1);
        var g=games.get(id);
        games.performAction(id,token,null,"human",new ActionRequest(ActionType.PLACE,null,g.legalPlacements().get(0),g.version()));
        s.onGameCommitted(games.get(id)); assertThat(calls.get()).isEqualTo(4);
    }
    @Test void rejectionReleasesInflightAndWatchdogCanRetry() {
        UUID id=create(PlayerColor.BLACK);
        var s=scheduler(new AlphaBetaMorrisAi(),task->{throw new RejectedExecutionException("full");});
        s.onGameCommitted(games.get(id)); assertThat(s.isInFlight(id)).isFalse();
        scheduler(new AlphaBetaMorrisAi(),Runnable::run).schedule(id,true);
        assertThat(games.get(id).version()).isEqualTo(1);
    }
    @Test void synchronousAfterCommitUsesIndependentWriteTransaction() {
        var s=scheduler(new AlphaBetaMorrisAi(),Runnable::run);
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            UUID id=create(PlayerColor.BLACK);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void afterCommit(){ s.onGameCommitted(games.get(id)); }
                    });
        });
        assertThat(games.get(ids.get(0)).version()).isEqualTo(1);
    }
}
