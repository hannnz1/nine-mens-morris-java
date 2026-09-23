package io.github.hannnz1.morris.backend.api;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// PostgresIntegrationTest's @SpringBootTest defaults to WebEnvironment.MOCK, which doesn't start
// an embedded server or expose a TestRestTemplate/@LocalServerPort. Redeclaring @SpringBootTest
// here (Spring resolves the closest one in the class hierarchy) switches just this test class to
// RANDOM_PORT so real HTTP round trips through Authorization headers work as written.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayerApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    void createThenFetchThenRenameRoundTrips() {
        String clientToken = "x".repeat(43);
        var createResponse = rest.postForEntity(url("/api/v1/players"),
                Map.of("nickname", "Han", "clientToken", clientToken), Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken);
        var meResponse = rest.exchange(url("/api/v1/players/me"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(meResponse.getBody().get("nickname")).isEqualTo("Han");

        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var renameResponse = rest.exchange(url("/api/v1/players/me"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("nickname", "HanZ"), headers), Map.class);
        assertThat(renameResponse.getBody().get("nickname")).isEqualTo("HanZ");
    }

    @Test
    void meFailsWithoutABearerToken() {
        var response = rest.getForEntity(url("/api/v1/players/me"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REQUIRED");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
