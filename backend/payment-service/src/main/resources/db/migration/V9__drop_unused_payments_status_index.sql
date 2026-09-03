-- ============================================================
-- V9: drop idx_payments_status, which nothing has ever read
--
-- PaymentJpaRepository exposes findByBookingId and what JpaRepository gives it
-- (findById/save/...). No derived query, JPQL query or native query anywhere in
-- payment-service filters, sorts or groups by `status`, so this index has only
-- ever cost writes: `payments` is updated on every state transition of every
-- payment (INITIATED -> SUCCEEDED/FAILED, and again on refund), and each of
-- those updates has been maintaining a second B-tree for no reader.
--
-- Same reasoning as identity's V4 and inventory's V4. Should a status-filtered
-- query ever be added, add the index back alongside it.
-- ============================================================

DROP INDEX IF EXISTS idx_payments_status;
