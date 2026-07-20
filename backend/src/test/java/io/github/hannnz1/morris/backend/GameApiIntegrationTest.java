package io.github.hannnz1.morris.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.persistence.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @Autowired
    private GameSessionRepository games;

    @BeforeEach
    void cleanDatabase() {
        idempotencyRecords.deleteAll();
        games.deleteAll();
    }

    @Test
    void createsAndReadsAGameWithoutExposingCredentials() throws Exception {
        CreatedGame created = createGame();

        mockMvc.perform(get("/api/v1/games/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.phase").value("PLACING"))
                .andExpect(jsonPath("$.whitePlayer").value("Alice"))
                .andExpect(jsonPath("$.whiteCredential").doesNotExist())
                .andExpect(jsonPath("$.blackCredential").doesNotExist());
    }

    @Test
    void appliesAnActionAndReturnsTheSameResponseForAnIdempotentRetry() throws Exception {
        CreatedGame created = createGame();
        String body = actionBody("PLACE", null, "A1", 0);

        MvcResult first = submit(created, created.whiteToken(), "place-white-a1", body, 200);
        MvcResult retry = submit(created, created.whiteToken(), "place-white-a1", body, 200);

        JsonNode firstJson = json(first);
        JsonNode retryJson = json(retry);
        assertThat(firstJson.get("version").asLong()).isEqualTo(1);
        assertThat(retryJson).isEqualTo(firstJson);
        assertThat(games.findById(created.id()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForADifferentAction() throws Exception {
        CreatedGame created = createGame();
        submit(created, created.whiteToken(), "same-key",
                actionBody("PLACE", null, "A1", 0), 200);

        submit(created, created.whiteToken(), "same-key",
                actionBody("PLACE", null, "D1", 0), 409)
                .getResponse();
    }

    @Test
    void rejectsAStaleVersionInsteadOfOverwritingNewerState() throws Exception {
        CreatedGame created = createGame();
        submit(created, created.whiteToken(), "white-1",
                actionBody("PLACE", null, "A1", 0), 200);

        MvcResult result = submit(created, created.blackToken(), "black-stale",
                actionBody("PLACE", null, "A4", 0), 409);
        assertThat(json(result).get("code").asText()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void rejectsAnInvalidPlayerToken() throws Exception {
        CreatedGame created = createGame();
        MvcResult result = submit(created, "not-a-valid-token", "invalid-token",
                actionBody("PLACE", null, "A1", 0), 403);
        assertThat(json(result).get("code").asText()).isEqualTo("INVALID_PLAYER_TOKEN");
    }

    @Test
    void mapsIllegalGameActionsToAStableApiError() throws Exception {
        CreatedGame created = createGame();
        submit(created, created.whiteToken(), "white-a1",
                actionBody("PLACE", null, "A1", 0), 200);
        submit(created, created.blackToken(), "black-occupied",
                actionBody("PLACE", null, "A1", 1), 422)
                .getResponse();
    }

    @Test
    void validatesCreateRequests() throws Exception {
        mockMvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"whitePlayer\":\"\",\"blackPlayer\":\"Bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("whitePlayer"));
    }

    @Test
    void returnsAStableErrorForUnsupportedActionValues() throws Exception {
        CreatedGame created = createGame();

        mockMvc.perform(post("/api/v1/games/{id}/actions", created.id())
                        .header("X-Player-Token", created.whiteToken())
                        .header("Idempotency-Key", "invalid-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TELEPORT\",\"to\":\"A1\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    private CreatedGame createGame() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"whitePlayer\":\" Alice \",\"blackPlayer\":\"Bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.game.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.whiteCredential.token").isNotEmpty())
                .andExpect(jsonPath("$.blackCredential.token").isNotEmpty())
                .andReturn();
        JsonNode response = json(result);
        return new CreatedGame(
                UUID.fromString(response.at("/game/id").asText()),
                response.at("/whiteCredential/token").asText(),
                response.at("/blackCredential/token").asText());
    }

    private MvcResult submit(CreatedGame game, String token, String key,
                             String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/games/{id}/actions", game.id())
                        .header("X-Player-Token", token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private String actionBody(String type, String from, String to, long version) throws Exception {
        return objectMapper.writeValueAsString(new ActionBody(type, from, to, version));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record CreatedGame(UUID id, String whiteToken, String blackToken) {
    }

    private record ActionBody(String type, String from, String to, long expectedVersion) {
    }
}
