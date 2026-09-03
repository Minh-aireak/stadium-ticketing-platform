-- ============================================================
-- V7: record a CONFIRMED booking whose seat sale ticket-inventory-service
-- will never accept, so the reconciler stops asking
--
-- V4 added inventory_confirmed so InventoryConfirmationReconciler could find
-- the bookings whose post-payment confirmReservation call failed and retry
-- them. That assumed every such failure is transient. One is not:
-- SeatAlreadySoldException (422) means the seat is SOLD to a DIFFERENT
-- booking -- this booking's Redis hold lapsed while the confirm was failing
-- and another customer bought the seat in the gap. 404 (the showtime has no
-- seat inventory at all) is equally final. Neither answer can change, so the
-- reconciler re-asked it every five minutes for the life of the row, and the
-- row could never leave its capped batch.
--
-- These bookings are CONFIRMED, paid for, and already have their confirmation
-- email out. Only a human can resolve one -- refund, or reseat -- which is the
-- same position payment-service's unreconciled_payments records, and it is
-- surfaced the same way: a gauge and a Prometheus alert, not an endless retry.
-- ============================================================

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS inventory_sale_refused BOOLEAN NOT NULL DEFAULT FALSE;

-- Replaces V4's index rather than adding a second one: the reconciler's query gained
-- "AND inventory_sale_refused = FALSE", so the old partial index no longer matches its
-- predicate and would be left behind covering nothing anyone asks for.
DROP INDEX IF EXISTS idx_bookings_pending_inventory_confirmation;

CREATE INDEX IF NOT EXISTS idx_bookings_pending_inventory_confirmation
    ON bookings (updated_at)
    WHERE status = 'CONFIRMED' AND inventory_confirmed = FALSE AND inventory_sale_refused = FALSE;

-- Counted on every scan by InventoryConfirmationReconciler for the booking.inventory.sale.refused
-- gauge, so it must not degrade into a sequential scan of every booking ever taken.
CREATE INDEX IF NOT EXISTS idx_bookings_inventory_sale_refused
    ON bookings (booking_id)
    WHERE inventory_sale_refused = TRUE;
