package com.aireak.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Match Catalog Service — Bounded Context: Catalog.
 * Manages Match/Showtime lifecycle. CQRS: PostgreSQL (write) + Elasticsearch (read).
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.aireak.catalog", "com.aireak.common"})
public class MatchCatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchCatalogServiceApplication.class, args);
    }
}
