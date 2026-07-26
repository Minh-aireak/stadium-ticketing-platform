package com.aireak.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for processed event persistence.
 * Internal to the adapter layer — not exposed to application or domain.
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventJpaEntity, String> {
    // existsById is inherited from JpaRepository — no additional queries needed
}
