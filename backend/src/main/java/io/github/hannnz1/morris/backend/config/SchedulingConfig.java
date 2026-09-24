package io.github.hannnz1.morris.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} task execution (backing {@code TimeoutScanner.scan()} and
 * {@code DowntimeCompensator.heartbeat()}), kept out of {@code MorrisBackendApplication} itself so
 * it can be switched off in tests via {@code morris.scheduling.enabled: false}. A live 1-second
 * timeout scan running unsupervised in the shared Spring test context would finish games in the
 * background and make clock tests that rely on a {@code MutableClock} flaky.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "morris.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
