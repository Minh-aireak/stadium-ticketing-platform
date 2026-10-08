-- ============================================================
-- V12: a payment can be refunded more than once, in parts
--
-- Customers now cancel paid seats one at a time (booking-service V8), each refunded at its own
-- price, so one payment can see several refunds. Two things follow.
--
-- payments.refunded_amount is what has gone back so far. A payment stays SUCCEEDED while part of
-- it is refunded and becomes REFUNDED once all of it is. Rows that were already REFUNDED were
-- refunded in full, so they start at their full amount.
--
-- payment_refunds is one row per refund request booking-service sent, keyed by its id. The status
-- check that used to make a redelivered request a no-op ("already REFUNDED") no longer can — the
-- payment is still SUCCEEDED after a partial refund — so this key is what does. The row is written
-- in the same transaction as the payment update, so the two never disagree.
-- ============================================================

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS refunded_amount NUMERIC(15, 2) NOT NULL DEFAULT 0;

UPDATE payments SET refunded_amount = amount WHERE status = 'REFUNDED';

CREATE TABLE IF NOT EXISTS payment_refunds (
    refund_request_id  VARCHAR(64)    PRIMARY KEY,
    payment_id         VARCHAR(36)    NOT NULL REFERENCES payments (payment_id),
    booking_id         VARCHAR(36)    NOT NULL,
    amount             NUMERIC(15, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    gateway_refund_id  VARCHAR(100)   NOT NULL,
    reason             VARCHAR(500),
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_refunds_payment ON payment_refunds (payment_id);
