package com.aireak.inventory.config;

import com.aireak.common.web.client.CorrelationIdRequestInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC now — no direct Kafka producer beans needed here anymore.
@Configuration
@EnableJpaAuditing
public class InfraConfig {
    @Bean(name = "catalogRestClient")
    public RestClient catalogRestClient() {
        // Was RestClient.create(). Built from the builder now only so the initializer can be
        // attached: it forwards X-Correlation-Id from the MDC, so match-catalog-service logs the
        // showtime lookup under the correlation ID of the hold request that caused it.
        return RestClient.builder()
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }
}
