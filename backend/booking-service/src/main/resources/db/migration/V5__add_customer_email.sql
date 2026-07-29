-- ============================================================
-- V5: denormalize the customer's email onto the booking row
--
-- BookingConfirmedEvent/BookingCancelledEvent need a real email address for
-- notification-service to send to, without notification-service calling back
-- into identity-service from inside a Kafka consumer. The email is captured
-- once at booking-creation time from the creator's own JWT (see
-- BookingController) and carried on the row so it's still available whenever
-- confirm()/cancel() later raises the event — no backfill for existing rows.
-- ============================================================

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255);
