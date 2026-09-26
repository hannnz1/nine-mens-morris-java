package io.github.hannnz1.morris.backend.service;

import io.github.hannnz1.morris.backend.api.ApiException;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.ai.Difficulty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class PlayerCapacityConcurrencyTest extends PostgresIntegrationTest {
    @Autowired GameSessionService games;
    @Autowired PlayerService players;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final List<UUID> humans=new ArrayList<>();
    String human(){String token=tokens.generate();humans.add(players.createOrGet(new CreatePlayerRequest("Capacity",token)).playerId());return token;}
    @AfterEach void cleanup(){for(var id:humans)jdbc.update("update game_sessions set status='CANCELLED' where white_player_id=? or black_player_id=?",id,id);}

    @ParameterizedTest @ValueSource(strings={"HUMAN","BOT","JOIN","REMATCH"})
    void allAdmissionsRespectAnotherUncommittedFifthGame(String mode) throws Exception {
        String token=human();var player=players.requirePlayer(token);
        var request=new BotGameRequest(Difficulty.EASY,PlayerColor.WHITE,"5+3");
        UUID target;
        if(mode.equals("REMATCH")){
            target=games.createBotGame(player,"original",request).game().id();
            games.resign(target,token,"finish");
        }else if(mode.equals("JOIN"))target=games.createForPlayer(players.requirePlayer(human()),"join-target","5+3").game().id();
        else target=null;
        for(int i=0;i<4;i++)games.createForPlayer(player,"initial"+i,"5+3");
        var uncommitted=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                games.createForPlayer(player,"fifth","5+3");uncommitted.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return true;
            }));
            assertThat(uncommitted.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                started.countDown();
                try{
                    switch(mode){
                        case "BOT" -> games.createBotGame(player,"sixth",request);
                        case "JOIN" -> games.joinByBearer(target,player,"sixth");
                        case "REMATCH" -> games.offerRematch(target,token,"sixth","OFFER");
                        default -> games.createForPlayer(player,"sixth","5+3");
                    }
                    return "CREATED";
                }catch(ApiException e){return e.code();}
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            // Give the old unlocked count path time to commit; the fixed path waits for the player lock.
            try{second.get(1,TimeUnit.SECONDS);}catch(TimeoutException expected){}
            finally{release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo("TOO_MANY_ACTIVE_GAMES");
            assertThat(jdbc.queryForObject("select count(*) from game_sessions where status in ('IN_PROGRESS','WAITING_FOR_PLAYER') and (white_player_id=? or black_player_id=?)",Integer.class,player.getId(),player.getId())).isEqualTo(5);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
