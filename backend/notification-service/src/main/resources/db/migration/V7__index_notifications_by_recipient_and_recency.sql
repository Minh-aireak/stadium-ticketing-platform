-- ============================================================
-- V7: let the notification list read its page straight out of the index
--
-- GET /api/v1/notifications reads findByRecipientId with
-- PageRequest.of(page, size, Sort.by(DESC, "createdAt")) -- see
-- NotificationPersistenceAdapter. idx_notifications_recipient covered only the
-- filter, so Postgres had to fetch every notification the recipient has ever
-- received and sort them to hand back one page of twenty. That cost grows with
-- the user's history, and it grows on the read path of a page users open often.
--
-- The composite serves the filter and the ordering together, so the page comes
-- off the index in order. DESC is spelled out to match the sort direction the
-- adapter asks for; Postgres can scan either way, but matching it keeps the plan
-- honest if a second sort key is ever added.
--
-- The old single-column index is dropped rather than kept: it is a strict
-- column-prefix of the new one, exactly the case identity's V4 and inventory's
-- V4 dropped.
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created_at
    ON notifications (recipient_id, created_at DESC);

DROP INDEX IF EXISTS idx_notifications_recipient;
