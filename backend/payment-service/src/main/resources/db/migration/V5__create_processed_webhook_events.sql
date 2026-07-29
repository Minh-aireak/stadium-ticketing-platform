-- ============================================================
-- V5: processed Stripe webhook event ids — idempotency guard for
-- StripeWebhookController so a redelivered event (Stripe retries
-- until it sees 2xx) is never reconciled twice.
-- ============================================================

CREATE TABLE IF NOT EXISTS processed_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY, -- Stripe event id, e.g. 'evt_1AbC...'
    event_type  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
