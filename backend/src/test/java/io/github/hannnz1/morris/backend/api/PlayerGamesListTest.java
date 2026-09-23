package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// PostgresIntegrationTest's @SpringBootTest defaults to WebEnvironment.MOCK, which doesn't start
// an embedded server or expose a TestRestTemplate/@LocalServerPort. Redeclaring @SpringBootTest
// here (as PlayerApiIntegrationTest and RoomCodeIntegrationTest already do) switches just this
// test class to RANDOM_PORT so real HTTP round trips through Authorization headers work.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayerGamesListTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @LocalServerPort
    private int port;

    @Test
    void listsOnlyTheCallersActiveGamesMostRecentFirst() {
        String clientToken = "z".repeat(43);
        rest.postForEntity(url("/api/v1/players"), Map.of("nickname", "Alice", "clientToken", clientToken), Map.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < 2; i++) {
            headers.set("Idempotency-Key", "create-list-" + i);
            rest.exchange(url("/api/v1/games"), HttpMethod.POST, new HttpEntity<>(Map.of(), headers), Map.class);
        }

        headers.remove("Idempotency-Key");
        var listResponse = rest.exchange(url("/api/v1/players/me/games?status=ACTIVE&limit=20"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> games = (List<?>) listResponse.getBody().get("games");
        assertThat(games).hasSize(2);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
