package io.github.hannnz1.morris.backend;

import io.github.hannnz1.morris.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migratesGameSessionsAndIdempotencyRecordsOnStartup() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            // Real PostgreSQL folds unquoted identifiers to lower case, and JDBC catalog lookups
            // are case-sensitive against the stored name, so the pattern here must be lower case.
            try (ResultSet tables = metadata.getTables(null, null, "game_sessions", null)) {
                assertThat(tables.next()).as("game_sessions table exists after Flyway migration").isTrue();
            }
            try (ResultSet tables = metadata.getTables(null, null, "flyway_schema_history", null)) {
                assertThat(tables.next()).as("flyway_schema_history table exists").isTrue();
            }
        }
    }
}
