-- ============================================================
-- V4: track whether a CONFIRMED booking's seat sale was actually
-- finalized in ticket-inventory-service
--
-- confirmReservation() is a best-effort REST call made AFTER a booking is
-- already marked CONFIRMED (payment succeeded) — see
-- TicketInventoryRestAdapter#confirmReservationFallback. If that call fails
-- (circuit open / retries exhausted), the seat's Redis hold silently expires
-- via TTL even though payment succeeded. inventory_confirmed lets
-- InventoryConfirmationReconciler find exactly those bookings and retry,
-- instead of re-scanning every CONFIRMED booking ever.
-- ============================================================

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS inventory_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_pending_inventory_confirmation
    ON bookings (updated_at)
    WHERE status = 'CONFIRMED' AND inventory_confirmed = FALSE;
