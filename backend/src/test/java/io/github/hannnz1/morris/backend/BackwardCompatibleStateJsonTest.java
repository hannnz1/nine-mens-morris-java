package io.github.hannnz1.morris.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hannnz1.morris.engine.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class BackwardCompatibleStateJsonTest extends io.github.hannnz1.morris.backend.support.PostgresIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aPreM2StateJsonWithNoDrawFieldsDeserializesWithSafeDefaults() throws Exception {
        // Exactly the shape create()/createForPlayer() wrote before this plan: no positionCounts,
        // pliesSinceRemoval, or drawReason keys at all.
        String preM2Json = """
                {"board":{},"currentPlayer":"WHITE","whitePiecesToPlace":9,"blackPiecesToPlace":9,
                 "removalPending":false,"winner":null,"turnNumber":0}
                """.replace("\"board\":{}", "\"board\":" + emptyBoardJson());

        GameState state = objectMapper.readValue(preM2Json, GameState.class);

        assertThat(state.positionCounts()).isEmpty();
        assertThat(state.pliesSinceRemoval()).isZero();
        assertThat(state.drawReason()).isNull();
    }

    private static String emptyBoardJson() {
        StringBuilder json = new StringBuilder("{");
        for (var position : io.github.hannnz1.morris.engine.BoardPosition.values()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(position).append("\":\"EMPTY\"");
        }
        return json.append('}').toString();
    }
}
