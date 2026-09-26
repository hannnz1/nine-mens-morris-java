package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import io.github.hannnz1.morris.backend.service.*;
import io.github.hannnz1.morris.backend.api.PlayerApiDtos.CreatePlayerRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BotGameIntegrationTest extends PostgresIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired PlayerService players;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    final List<UUID> humans = new ArrayList<>();
    String human() {
        String token = tokens.generate();
        humans.add(players.createOrGet(new CreatePlayerRequest("Tester", token)).playerId());
        return token;
    }
    @AfterEach void cleanup() {
        for (UUID id : humans) jdbc.update("update game_sessions set status='CANCELLED' where white_player_id=? or black_player_id=?", id, id);
    }
    ResponseEntity<Map> post(String path, String token, String key, Object body) {
        var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        h.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
    Map<String,Object> body(String color) { return Map.of("opponent","BOT","difficulty","MEDIUM","color",color,"timeControl","5+3"); }
    Map game(ResponseEntity<Map> r) { assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED); return (Map) r.getBody().get("game"); }
    @Test void createsBothColorsWithNoRoomAndAnImmediateClock() {
        String token = human();
        for (String color : List.of("WHITE","BLACK")) {
            var response = post("/api/v1/games", token, UUID.randomUUID().toString(), body(color));
            var g = game(response);
            assertThat(g.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(g.get("botSide")).isEqualTo(color.equals("WHITE") ? "BLACK" : "WHITE");
            assertThat(g.get("botDifficulty")).isEqualTo("MEDIUM");
            assertThat(response.getBody().get("roomCode")).isNull();
            assertThat(((Map)g.get("clock")).get("running")).isEqualTo(true);
            assertThat(post("/api/v1/games/"+g.get("id")+"/join",human(),"join",Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
    @Test void randomIsIdempotentAndChangedParametersOrOpponentAreRejected() {
        String token = human(), key = UUID.randomUUID().toString();
        var first = post("/api/v1/games",token,key,body("RANDOM"));
        assertThat(game(first).get("botDifficulty")).isEqualTo("MEDIUM");
        assertThat(post("/api/v1/games",token,key,body("RANDOM")).getBody()).isEqualTo(first.getBody());
        var changed = new HashMap<>(body("RANDOM")); changed.put("difficulty","HARD");
        assertThat(post("/api/v1/games",token,key,changed).getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(post("/api/v1/games",token,key,Map.of("opponent","HUMAN")).getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        var humanKey = UUID.randomUUID().toString();
        game(post("/api/v1/games",token,humanKey,Map.of()));
        assertThat(post("/api/v1/games",token,humanKey,body("RANDOM")).getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
    @Test void validatesBotParametersAndRequiresBearer() {
        String token = human();
        for (var invalid : List.of(Map.of("opponent","BOT"), Map.of("opponent","BOT","difficulty","BAD"),
                Map.of("opponent","BAD"), Map.of("opponent","BOT","difficulty","EASY","color","BAD"),
                Map.of("opponent","BOT","difficulty",123), Map.of("opponent","BOT","difficulty","EASY","timeControl","1+0"))) {
            var r = post("/api/v1/games",token,UUID.randomUUID().toString(),invalid);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(post("/api/v1/games",null,"no-auth",body("WHITE")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void botsDeclineDrawAndAcceptRematchWithSwappedColor() {
        String token = human();
        var original = game(post("/api/v1/games",token,"create",body("WHITE")));
        String path = "/api/v1/games/"+original.get("id");
        assertThat(post(path+"/draw",token,"draw",Map.of("action","OFFER")).getBody().get("code")).isEqualTo("DRAW_NOT_AVAILABLE");
        assertThat(post(path+"/resign",token,"resign",Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        var rematch = post(path+"/rematch",token,"again",Map.of("action","OFFER"));
        assertThat(rematch.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object newId = rematch.getBody().get("rematchGameId"); assertThat(newId).isNotNull();
        var next = rest.getForObject("/api/v1/games/"+newId,Map.class);
        assertThat(next.get("botSide")).isEqualTo("WHITE");
        assertThat(next.get("botDifficulty")).isEqualTo("MEDIUM");
        assertThat(jdbc.queryForObject("select room_code from game_sessions where id=?",String.class,UUID.fromString(newId.toString()))).isNull();
    }
    @Test void enforcesHumanLimitButBotCanPlayMoreThanFiveGames() {
        String token = human();
        for(int i=0;i<5;i++) game(post("/api/v1/games",token,"c"+i,body("WHITE")));
        assertThat(post("/api/v1/games",token,"six",body("WHITE")).getBody().get("code")).isEqualTo("TOO_MANY_ACTIVE_GAMES");
        assertThat(game(post("/api/v1/games",human(),"six-bot",body("WHITE"))).get("status")).isEqualTo("IN_PROGRESS");
    }
    @Test void concurrentCreatesCannotExceedGlobalLimit() throws Exception {
        for (int p=0;p<4;p++) {
            String token = human();
            for (int n=0;n<(p==3?4:5);n++) game(post("/api/v1/games",token,"cap"+n,body("WHITE")));
        }
        String a=human(), b=human();
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var x=pool.submit(() -> { latch.await(); return post("/api/v1/games",a,"last-a",body("WHITE")); });
            var y=pool.submit(() -> { latch.await(); return post("/api/v1/games",b,"last-b",body("WHITE")); });
            latch.countDown();
            var results=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.getStatusCode()==HttpStatus.CREATED).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> "BOT_BUSY".equals(r.getBody().get("code"))).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void rematchAndCreateCompeteForTheSameFinalCapacitySlot() throws Exception {
        String rematcher=human();
        var old=game(post("/api/v1/games",rematcher,"original",body("WHITE")));
        post("/api/v1/games/"+old.get("id")+"/resign",rematcher,"finish",Map.of());
        for(int p=0;p<4;p++){
            String token=human();for(int n=0;n<(p==3?4:5);n++)game(post("/api/v1/games",token,"fill"+n,body("WHITE")));
        }
        String creator=human();var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{latch.await();return post("/api/v1/games/"+old.get("id")+"/rematch",rematcher,"again",Map.of("action","OFFER"));});
            var b=pool.submit(()->{latch.await();return post("/api/v1/games",creator,"last",body("WHITE"));});
            latch.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.getStatusCode().is2xxSuccessful()).count()).isEqualTo(1);
            assertThat(results.stream().filter(r->"BOT_BUSY".equals(r.getBody().get("code"))).count()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
}
