package com.aireak.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for processed webhook event persistence.
 * Internal to the adapter layer — not exposed to application or domain.
 */
interface ProcessedWebhookEventJpaRepository extends JpaRepository<ProcessedWebhookEventJpaEntity, String> {
    // existsById is inherited from JpaRepository — no additional queries needed
}
