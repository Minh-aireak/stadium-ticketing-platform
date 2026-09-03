-- Stripe stores the response of the first request made with a given idempotency key and replays it
-- for every later request presenting that key, for 24 hours, whether the stored response was a
-- success or an error. PaymentService#retry re-charged with the same bookingId the original attempt
-- used, so within that window a retry could not do anything at all: it got the original decline
-- handed back to it. POST /api/v1/payments/{id}/retry could never succeed.
--
-- charge_attempt is what lets a retry present a key the gateway has not seen. It is deliberately
-- NOT bumped after an ambiguous failure -- there the old key is the whole point, because replaying
-- it is how a charge whose response was lost gets recovered rather than made a second time.
-- See Payment#retry and Payment#chargeIdempotencyKey.
ALTER TABLE payments
    ADD COLUMN charge_attempt INTEGER NOT NULL DEFAULT 0;
