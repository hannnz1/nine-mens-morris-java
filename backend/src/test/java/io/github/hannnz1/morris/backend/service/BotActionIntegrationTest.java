package io.github.hannnz1.morris.backend.service;
import io.github.hannnz1.morris.backend.api.*;
import io.github.hannnz1.morris.backend.api.GameApiDtos.*;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.engine.*;
import io.github.hannnz1.morris.engine.ai.Difficulty;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BotActionIntegrationTest extends PostgresIntegrationTest {
    @Autowired GameSessionService games;
    @Autowired PlayerService players;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    String token; UUID id;
    @BeforeEach void create() {
        token=tokens.generate(); players.createOrGet(new CreatePlayerRequest("Human",token));
        id=games.createBotGame(players.requirePlayer(token), UUID.randomUUID().toString(),
                new BotGameRequest(Difficulty.EASY,PlayerColor.BLACK,"5+3")).game().id();
    }
    @AfterEach void cleanup() { jdbc.update("update game_sessions set status='CANCELLED' where id=?",id); }
    ActionRequest place(BoardPosition p,long v) { return new ActionRequest(ActionType.PLACE,null,p,v); }
    @Test void sameVersionDifferentMovesReplayExactlyOneWrite() {
        var first=games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.A1,0));
        var replay=games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.D1,0));
        assertThat(replay).isEqualTo(first);
        assertThat(games.get(id).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where game_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void onlyTheSeatedBotMayUseInternalActionPath() {
        assertThatThrownBy(() -> games.performBotAction(id,players.requirePlayer(token).getId(),place(BoardPosition.A1,0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> games.performBotAction(id,BotRoster.id(Difficulty.HARD),place(BoardPosition.A1,0))).isInstanceOf(IllegalArgumentException.class);
        assertThat(games.get(id).version()).isZero();
    }
    @Test void staleVersionAndResignationPreventNewWrites() {
        assertThatThrownBy(() -> games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.A1,12))).isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code","VERSION_CONFLICT");
        games.resign(id,token,"resign");
        var before=games.get(id);
        assertThatThrownBy(() -> games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.A1,before.version()))).isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code","GAME_NOT_ACTIVE");
        assertThat(games.get(id)).usingRecursiveComparison().ignoringFields("clock.serverNow").isEqualTo(before);
    }
    @Test void timeoutIsCommittedAndReplayedAsRejectedRatherThanRolledBack() {
        jdbc.update("update game_sessions set turn_deadline_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' where id=?",id);
        var first=games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.A1,0));
        assertThat(first.rejectedByTimeout()).isTrue();
        assertThat(games.get(id).status()).isEqualTo("ABORTED");
        assertThat(games.performBotAction(id,BotRoster.id(Difficulty.EASY),place(BoardPosition.D1,0))).isEqualTo(first);
    }
}
