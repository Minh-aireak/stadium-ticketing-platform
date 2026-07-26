package com.aireak.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC now — no direct Kafka producer beans needed here anymore.
@Configuration
@EnableJpaAuditing
public class InfraConfig {
}
