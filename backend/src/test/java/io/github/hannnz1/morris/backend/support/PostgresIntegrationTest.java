package io.github.hannnz1.morris.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL 16 in a disposable container, migrated by Flyway like production.
 *
 * <p>The container is started once (a "singleton container", not {@code @Testcontainers}/
 * {@code @Container}-managed) and shared by every subclass for the life of the JVM, instead of
 * being stopped and restarted around each test class. Restarting the same {@code GenericContainer}
 * instance between test classes proved flaky under this suite's growing number of Postgres-backed
 * test classes (intermittent "connection refused" right after a reported successful restart);
 * Testcontainers itself recommends this singleton pattern for exactly this reason. The container is
 * reaped by Testcontainers' Ryuk sidecar when the JVM exits.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The "test" profile hardcodes the H2 driver and an H2-flavored connection-init-sql for
        // the H2-backed suites that don't use Testcontainers; override both here so Hikari,
        // Flyway and Hibernate talk to the real PostgreSQL container instead, without touching
        // application-test.yml (which the H2 suites still rely on unmodified).
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '5s'");
    }
}
