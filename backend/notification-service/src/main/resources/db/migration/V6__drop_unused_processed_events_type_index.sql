-- ============================================================
-- V6: drop idx_processed_events_type, which nothing has ever read
--
-- processed_events is written once per consumed event and read only by the
-- existsById idempotency check, which goes to the primary key. event_type is
-- recorded for operators reading the table by hand, never queried by the
-- service -- ProcessedEventJpaRepository has existsById and the retention
-- delete on created_at, and nothing else.
--
-- The retention delete added by the cleanup scheduler filters on created_at,
-- not event_type, so it does not want this index either. It stays unindexed on
-- purpose: it runs once a day and a sequential scan of a table that is now
-- bounded to 14 days is cheaper than a second B-tree on every insert.
-- ============================================================

DROP INDEX IF EXISTS idx_processed_events_type;
