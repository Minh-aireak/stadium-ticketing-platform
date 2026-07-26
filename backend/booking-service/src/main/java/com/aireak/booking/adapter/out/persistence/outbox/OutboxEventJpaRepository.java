package com.aireak.booking.adapter.out.persistence.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
