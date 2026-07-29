package com.aireak.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC now — no direct Kafka producer beans needed here anymore.
@Configuration
@EnableJpaAuditing
@EnableAsync
public class InfraConfig {

    /** Backs {@code MatchSearchIndexer#indexAsync} — small pool, this only runs on publish. */
    @Bean(name = "matchSearchIndexExecutor")
    public Executor matchSearchIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("match-search-index-");
        executor.initialize();
        return executor;
    }
}
