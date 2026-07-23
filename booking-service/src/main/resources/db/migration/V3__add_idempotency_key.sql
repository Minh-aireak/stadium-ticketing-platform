-- ============================================================
-- V3: idempotency key for POST /api/v1/bookings
--
-- Client-supplied "Idempotency-Key" header, stored alongside the booking it
-- created. NULL for requests that didn't send one (kept optional, not every
-- caller is required to support retries this way). The partial unique index
-- only constrains non-null values, so it enforces "same key -> same booking"
-- without blocking NULL from repeating.
-- ============================================================

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bookings_idempotency_key
    ON bookings (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
