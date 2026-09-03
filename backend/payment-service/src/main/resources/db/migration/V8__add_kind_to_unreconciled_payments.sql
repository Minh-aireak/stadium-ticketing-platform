-- ============================================================
-- V8: distinguish a stuck CHARGE from a stuck REFUND
--
-- unreconciled_payments only ever held charges whose SUCCEEDED outcome could
-- not be persisted. The refund path now records here too (see
-- PaymentService#persistRefundedOutcome), and the two need opposite manual
-- fixes: a stuck CHARGE means the customer paid and the booking never
-- confirmed, a stuck REFUND means the money went back but `payments` still
-- reads SUCCEEDED. Without this column an operator cannot tell which of the
-- two a row is, and gateway_transaction_id alone does not say — it holds a
-- PaymentIntent id for one and a Refund id for the other.
--
-- Existing rows are all charges by construction, hence the DEFAULT.
-- ============================================================

ALTER TABLE unreconciled_payments
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'CHARGE';

ALTER TABLE unreconciled_payments
    ADD CONSTRAINT ck_unreconciled_payments_kind CHECK (kind IN ('CHARGE', 'REFUND'));
