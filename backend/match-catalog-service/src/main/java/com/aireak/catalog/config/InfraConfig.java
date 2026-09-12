package com.aireak.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC, so no domain-event producer lives here. The one Kafka producer this service does
// have is KafkaConfig#deadLetterKafkaTemplate, which republishes records that failed every retry —
// no transaction to stay consistent with, so no outbox to ride.
//
// No @EnableAsync and no executor any more: the search index used to be written on an @Async
// pool right after publish, with no retry. It is now written by MatchSearchIndexConsumer from the
// service's own lifecycle events (see MatchSearchIndexReconciler), so nothing here runs off the
// request thread.
@Configuration
@EnableJpaAuditing
public class InfraConfig {
}
