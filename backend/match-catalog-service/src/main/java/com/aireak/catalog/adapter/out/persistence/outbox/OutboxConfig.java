package com.aireak.catalog.adapter.out.persistence.outbox;

import com.aireak.common.outbox.OutboxEventCleanupScheduler;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Opts this service into the shared outbox in {@code com.aireak.common.outbox}, which Boot's
 * defaults (the package of {@code MatchCatalogServiceApplication}) would not otherwise reach.
 *
 * <p>Everything here is opt-in rather than automatic for two different reasons.
 * notification-service also component-scans {@code com.aireak.common} but has no
 * {@code outbox_events} table, so neither the entity nor the purge job may activate merely by
 * being on the classpath. And these two scan annotations deliberately sit on a scanned
 * {@code @Configuration} rather than on the application class: {@code @EnableJpaRepositories}
 * there would apply to the {@code @WebMvcTest} slices too, which have no
 * {@code entityManagerFactory} to give the repositories and would fail to start.
 *
 * <p>Each annotation REPLACES the default rather than adding to it, so this service's own package
 * is listed alongside. A {@code @DataJpaTest} that needs the outbox imports this class.
 */
@Configuration
@EntityScan(basePackages = {"com.aireak.catalog", "com.aireak.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.aireak.catalog", "com.aireak.common.outbox"})
public class OutboxConfig {

    @Bean
    public OutboxEventCleanupScheduler outboxEventCleanupScheduler(
            OutboxEventJpaRepository outboxEventJpaRepository,
            @Value("${outbox.cleanup.retention-days:14}") int retentionDays) {
        return new OutboxEventCleanupScheduler(outboxEventJpaRepository, retentionDays);
    }
}
