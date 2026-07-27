-- ============================================================
-- V3: transactional outbox for payment-service domain events
--
-- Debezium tails this table via logical replication (CDC) and
-- routes each row to a Kafka topic using the outbox event router
-- SMT — see payment-service/infra/debezium/payment-outbox-connector.json.
-- Application code writes rows here in the SAME transaction as the
-- Payment aggregate save, replacing the old direct KafkaTemplate.send() call.
-- ============================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL, -- target Kafka topic, e.g. 'payment.payment.succeeded'
    aggregate_id    VARCHAR(255) NOT NULL, -- paymentId — used as the Kafka message key
    event_type      VARCHAR(255) NOT NULL, -- simple event class name, e.g. 'PaymentSucceededEvent'
    payload         JSONB        NOT NULL, -- serialized EventEnvelope JSON (matches prior Kafka message body)
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_created_at ON outbox_events (created_at);
