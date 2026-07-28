-- ============================================================
-- V2: in-app notifications (distinct from the email/SMS side effects
-- NotificationDispatchService also sends) — backs GET /api/v1/notifications.
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications (
    notification_id VARCHAR(36)   PRIMARY KEY,
    recipient_id    VARCHAR(36)   NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    read            BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient_id);
