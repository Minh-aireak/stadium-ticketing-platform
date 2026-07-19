-- ============================================================
-- V1: notification-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(36)  PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_processed_events_type ON processed_events (event_type);
