-- ============================================================
-- V3: per-showtime ticket pricing
--
-- basePrice/currency drive per-seat tier pricing snapshotted by
-- ticket-inventory-service when it generates the seat map off the
-- ShowtimeAddedEvent this now raises (see Match#addShowtime).
-- DEFAULT values only satisfy the NOT NULL constraint for any rows that
-- predate this migration; every new insert always supplies real values.
-- ============================================================

ALTER TABLE showtimes
    ADD COLUMN IF NOT EXISTS base_price NUMERIC(15, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS currency   CHAR(3)        NOT NULL DEFAULT 'VND';
