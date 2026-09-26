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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lateFailureCannotResurrectStateAfterTerminationOrClose(boolean close) throws Exception {
        UUID id=create(PlayerColor.BLACK);
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var s=scheduler((state,d,seed,b)->{
            started.countDown();
            try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            throw new IllegalStateException("late search failure");
        },pool);
        try{
            s.schedule(id,true);assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if(close)s.close();else s.onGameCommitted(games.resign(id,token,"finish"));
            release.countDown();pool.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(s,"failures")).isEmpty();
            assertThat(s.isInFlight(id)).isFalse();
            assertThat(games.get(id).state().piecesOnBoard(Player.WHITE)).isZero();
        }finally{release.countDown();s.close();pool.shutdownNow();}
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
    @Test void millAndCaptureFinishWithinTheSameScheduledTask() throws Exception {
        UUID id=create(PlayerColor.BLACK);
        var board=new EnumMap<BoardPosition,Piece>(BoardPosition.class);
        for(var p:BoardPosition.values())board.put(p,Piece.EMPTY);
        for(var p:List.of(BoardPosition.A1,BoardPosition.D1,BoardPosition.B2))board.put(p,Piece.WHITE);
        for(var p:List.of(BoardPosition.C3,BoardPosition.D3,BoardPosition.E5))board.put(p,Piece.BLACK);
        var state=new GameState(board,Player.WHITE,0,0,false,null,20,Map.of(),0,null);
        jdbc.update("update game_sessions set state_json=? where id=?",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(state),id);
        scheduler((position,d,seed,budget)->new AlphaBetaMorrisAi().chooseAction(position,Difficulty.MEDIUM,seed,budget),Runnable::run).schedule(id,true);
        assertThat(games.get(id).status()).isEqualTo("WHITE_WON");
        assertThat(games.get(id).state().piecesOnBoard(Player.BLACK)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where game_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void humanReplyDuringInflightCleanupIsNotLost() {
        UUID id=create(PlayerColor.BLACK);var ref=new java.util.concurrent.atomic.AtomicReference<BotTurnScheduler>();
        var replied=new java.util.concurrent.atomic.AtomicBoolean();
        var racingAccess=new BotGameAccess(games){
            @Override public GameResponse get(UUID g){return access.get(g);}
            @Override public ActionOutcome act(UUID g,UUID bot,ActionRequest request){
                var result=access.act(g,bot,request);
                if(replied.compareAndSet(false,true)) {
                    var snapshot=games.get(g);
                    games.performAction(g,token,null,"fast-human",new ActionRequest(ActionType.PLACE,null,snapshot.legalPlacements().get(0),snapshot.version()));
                    ref.get().onGameCommitted(games.get(g)); // inFlight still contains id here
                }
                return result;
            }
        };
        var s=new BotTurnScheduler(racingAccess,new AlphaBetaMorrisAi(),(r,ms)->r.run(),Runnable::run,()->true,Duration.ofMillis(20),0,0);
        ref.set(s);s.schedule(id,true);
        assertThat(games.get(id).version()).isEqualTo(3);
        assertThat(s.isInFlight(id)).isFalse();
    }
    @Test void delayRejectionAndStartupNotReadyLeaveNoInflightTask() {
        UUID id=create(PlayerColor.BLACK);
        var s=new BotTurnScheduler(access,new AlphaBetaMorrisAi(),(r,ms)->{throw new RejectedExecutionException();},Runnable::run,()->true,Duration.ofMillis(20),0,0);
        s.schedule(id,false);assertThat(s.isInFlight(id)).isFalse();
        var queued=new ArrayList<Runnable>();
        var notReady=new BotTurnScheduler(access,new AlphaBetaMorrisAi(),(r,ms)->queued.add(r),Runnable::run,()->false,Duration.ofMillis(20),0,0);
        notReady.schedule(id,false);assertThat(queued).isEmpty();assertThat(notReady.isInFlight(id)).isFalse();
    }
}
