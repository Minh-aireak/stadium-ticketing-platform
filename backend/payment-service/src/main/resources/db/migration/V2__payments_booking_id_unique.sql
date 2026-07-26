-- ============================================================
-- V2: enforce one payment per booking (idempotency backstop)
-- ============================================================

DROP INDEX IF EXISTS idx_payments_booking_id;
ALTER TABLE payments ADD CONSTRAINT uq_payments_booking_id UNIQUE (booking_id);
