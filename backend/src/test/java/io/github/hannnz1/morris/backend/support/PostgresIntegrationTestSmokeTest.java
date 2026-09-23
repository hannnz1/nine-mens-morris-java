package io.github.hannnz1.morris.backend.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresIntegrationTestSmokeTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void connectsToAMigratedRealPostgresDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT to_regclass('game_sessions')")) {
                rows.next();
                assertThat(rows.getString(1)).as("game_sessions exists after Flyway migration").isNotNull();
            }
        }
    }
}
