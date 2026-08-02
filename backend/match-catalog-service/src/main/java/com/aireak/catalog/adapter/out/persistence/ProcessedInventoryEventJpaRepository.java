package com.aireak.catalog.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedInventoryEventJpaRepository
        extends JpaRepository<ProcessedInventoryEventJpaEntity, String> {
}
