-- ============================================================
-- V1: identity-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS accounts (
    account_id      VARCHAR(36)  PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    hashed_password VARCHAR(255) NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_accounts_email ON accounts (email);
