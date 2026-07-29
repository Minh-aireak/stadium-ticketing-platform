package com.aireak.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Records a Stripe webhook event id once handled, so a retried delivery is a no-op. */
@Getter
@Entity
@Table(name = "processed_webhook_events")
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedWebhookEventJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ProcessedWebhookEventJpaEntity of(String eventId, String eventType) {
        return new ProcessedWebhookEventJpaEntity(eventId, eventType, Instant.now());
    }
}
