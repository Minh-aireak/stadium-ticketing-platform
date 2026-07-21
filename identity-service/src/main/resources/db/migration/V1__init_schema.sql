-- ============================================================
-- V1: identity-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS accounts (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    registered_at   TIMESTAMP    NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_accounts_email UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_accounts_email ON accounts (email);
