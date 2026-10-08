-- ============================================================
-- V8: customers cancel seats one at a time
--
-- A paid booking can now be cancelled seat by seat until the cancellation deadline (see
-- Booking#cancelSeatsByCustomer). The booking keeps every seat it was created with in seat_codes;
-- these two columns say which of them are no longer its, and how much has been refunded for them.
--
-- Both default to "nothing cancelled, nothing refunded", which is right for every existing row: a
-- booking cancelled as a whole before this migration is still read as having all of its seats
-- cancelled, because Booking derives that from status = 'CANCELLED' rather than from this column.
-- ============================================================

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS cancelled_seat_codes VARCHAR(1000)  NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS refunded_amount      NUMERIC(15, 2) NOT NULL DEFAULT 0;
