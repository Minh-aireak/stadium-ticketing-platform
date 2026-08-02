package com.aireak.inventory.config;

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
        return RestClient.create();
    }
}
