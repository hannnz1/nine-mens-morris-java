package io.github.hannnz1.morris.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.backend.persistence.GameSessionRepository;
import io.github.hannnz1.morris.backend.service.TokenService;
import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import io.github.hannnz1.morris.backend.config.GameWebSocketHandler;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class RecoveryIntegrationTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired GameSessionRepository games;
    @MockitoSpyBean GameWebSocketHandler messaging;

    @Test
    void lostJoinResponseCanBeRetriedOnlyWithProofAndPushFailureDoesNotUndoCommit() throws Exception {
        var created = mapper.readTree(mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                .content("{\"whitePlayer\":\"Alice\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = created.at("/game/id").asText();
        String white = created.at("/whiteCredential/token").asText();
        String proof = new TokenService().generate();
        String body = mapper.writeValueAsString(Map.of("blackPlayer", "Bob", "joinToken", proof));
        doThrow(new IllegalStateException("simulated unavailable push")).when(messaging).broadcast(eq(UUID.fromString(id)), any(io.github.hannnz1.morris.backend.api.GameApiDtos.GameResponse.class));
        var first = mvc.perform(post("/api/v1/games/{id}/join", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn(); // Deliberately discard this response at the client boundary.
        var retry = mvc.perform(post("/api/v1/games/{id}/join", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        var initial = mapper.readTree(first.getResponse().getContentAsString());
        var restored = mapper.readTree(retry.getResponse().getContentAsString());
        // Join restores the current snapshot, not a byte-for-byte cached response.
        assertThat(restored.get("credential")).isEqualTo(initial.get("credential"));
        assertThat(restored.at("/game/version")).isEqualTo(initial.at("/game/version"));
        assertThat(restored.at("/game/state")).isEqualTo(initial.at("/game/state"));
        mvc.perform(post("/api/v1/games/{id}/join", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("blackPlayer", "Bob", "joinToken", new TokenService().generate()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME_ALREADY_FULL"));
        mvc.perform(get("/api/v1/games/{id}/session", id).header("X-Player-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/games/{id}/actions", id).header("X-Player-Token", white).header("Idempotency-Key", "push-failure")
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"PLACE\",\"to\":\"A1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/games/{id}/session", id).header("X-Player-Token", proof))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state.board.A1").value("WHITE"))
                .andExpect(jsonPath("$.credential").doesNotExist());
        var saved = games.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getBlackTokenHash()).isEqualTo(new TokenService().hash(proof)).isNotEqualTo(proof);
    }
}
