-- ============================================================
-- V3: per-seat tier/price snapshot
--
-- Set by SeatMapLayout at seat-map generation time (off the catalog-service
-- ShowtimeAddedEvent, see ShowtimeAddedEventConsumer) and never recomputed
-- afterwards. DEFAULT values only satisfy the NOT NULL constraint for any
-- rows that predate this migration; every new insert always supplies real
-- values.
-- ============================================================

ALTER TABLE seats
    ADD COLUMN IF NOT EXISTS tier  VARCHAR(20)    NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN IF NOT EXISTS price NUMERIC(15, 2) NOT NULL DEFAULT 0;
