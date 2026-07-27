package com.aireak.catalog.adapter.out.persistence.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Modifying(clearAutomatically = true)
    @Query("delete from OutboxEventEntity e where e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
