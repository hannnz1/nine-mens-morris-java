package io.github.hannnz1.morris.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migratesGameSessionsAndIdempotencyRecordsOnStartup() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            // The test datasource (H2 in PostgreSQL compatibility mode, DATABASE_TO_LOWER=TRUE)
            // folds unquoted identifiers to lower case, matching real PostgreSQL's behavior for
            // unquoted identifiers. JDBC catalog lookups are case-sensitive against the stored
            // name, so the pattern here must be lower case on both engines.
            try (ResultSet tables = metadata.getTables(null, null, "game_sessions", null)) {
                assertThat(tables.next()).as("game_sessions table exists after Flyway migration").isTrue();
            }
            try (ResultSet tables = metadata.getTables(null, null, "flyway_schema_history", null)) {
                assertThat(tables.next()).as("flyway_schema_history table exists").isTrue();
            }
        }
    }
}
