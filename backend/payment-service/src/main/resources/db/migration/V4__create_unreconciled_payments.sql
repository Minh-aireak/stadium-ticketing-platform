-- ============================================================
-- V4: durable holding table for payments whose gateway charge succeeded
-- but whose SUCCEEDED outcome could not be persisted to the `payments`
-- table even after retrying — see PaymentService#persistSucceededOutcome.
--
-- A row here means the gateway (Stripe) actually charged the customer
-- (gateway_transaction_id is a real PaymentIntent id) but our own DB write
-- failed, so `payments` never moved to SUCCEEDED and PaymentSucceededEvent
-- was never published. This must be reconciled manually — verify the charge
-- with the gateway, fix the `payments` row, then mark `resolved = true` here.
-- ============================================================

CREATE TABLE IF NOT EXISTS unreconciled_payments (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id              VARCHAR(36)   NOT NULL,
    booking_id              VARCHAR(36)   NOT NULL,
    gateway_transaction_id  VARCHAR(255)  NOT NULL,
    amount                  NUMERIC(15,2) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    failure_reason          TEXT,
    resolved                BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_unreconciled_payments_unresolved
    ON unreconciled_payments (resolved) WHERE NOT resolved;
