package com.aireak.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "processed_inventory_events")
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ProcessedInventoryEventJpaEntity {
    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
