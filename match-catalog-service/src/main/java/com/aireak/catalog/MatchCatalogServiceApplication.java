package com.aireak.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Match Catalog Service — Bounded Context: Catalog.
 * Manages Match/Showtime lifecycle. CQRS: PostgreSQL (write) + Elasticsearch (read).
 */
@SpringBootApplication
public class MatchCatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MatchCatalogServiceApplication.class, args);
    }
}
