-- ============================================================
-- V3: processed_events was missing the created_at/updated_at columns
-- required by BaseAuditEntity (ProcessedEventJpaEntity extends it),
-- causing Hibernate schema validation to fail on startup.
-- ============================================================

ALTER TABLE processed_events
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT NOW();
