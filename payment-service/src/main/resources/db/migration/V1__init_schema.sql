-- ============================================================
-- V1: payment-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS payments (
    payment_id              VARCHAR(36)     PRIMARY KEY,
    booking_id              VARCHAR(36)     NOT NULL,
    amount                  NUMERIC(15, 2)  NOT NULL,
    currency                CHAR(3)         NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'INITIATED',
    gateway_transaction_id  VARCHAR(100),
    failure_reason          VARCHAR(500),
    created_at              TIMESTAMP       NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    updated_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_booking_id ON payments (booking_id);
CREATE INDEX IF NOT EXISTS idx_payments_status     ON payments (status);
