package io.github.hannnz1.morris.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrityErrorTest {
    @Test
    void onlyKnownUniqueConflictIsAConflictAndInternalDetailsAreHidden() {
        var handler = new GlobalExceptionHandler();
        for (String constraint : new String[]{"uk_idempotency_game_key", "unknown_constraint"}) {
            var cause = new ConstraintViolationException("secret SQL", new SQLException("secret detail", "23505"), constraint);
            var response = handler.handleConflict(new DataIntegrityViolationException("secret", cause), new MockHttpServletRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(constraint.startsWith("uk_") ? 409 : 500);
            assertThat(response.getBody().message()).doesNotContain("secret", "SQL", constraint);
        }
        assertThat(handler.handleConflict(new DataIntegrityViolationException("not null secret"), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(500);
    }
}
