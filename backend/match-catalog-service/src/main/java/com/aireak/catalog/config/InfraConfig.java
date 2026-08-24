package com.aireak.catalog.config;

import com.aireak.common.concurrent.MdcPropagatingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC, so no domain-event producer lives here. The one Kafka producer this service does
// have is KafkaConfig#deadLetterKafkaTemplate, which republishes records that failed every retry —
// no transaction to stay consistent with, so no outbox to ride.
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
        // Keeps the publishing request's correlation ID on the indexing thread. The index write has
        // no retry, so its failure log is the only record that a match never reached Elasticsearch —
        // untagged, that line cannot be tied back to the publish that should have produced it.
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
