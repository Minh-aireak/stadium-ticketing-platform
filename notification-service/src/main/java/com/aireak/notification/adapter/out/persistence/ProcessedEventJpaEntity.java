package com.aireak.notification.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity persisting processed event IDs for Kafka consumer idempotency.
 * Lives in adapter/out/persistence — domain knows nothing about this entity.
 */
@Getter
@Entity
@Table(name = "processed_events")
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEventJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
}
