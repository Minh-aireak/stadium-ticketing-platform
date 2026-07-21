-- ============================================================
-- V2: transactional outbox for identity-service domain events
--
-- Debezium tails this table via logical replication (CDC) and
-- routes each row to a Kafka topic using the outbox event router
-- SMT — see identity-service/infra/debezium/identity-outbox-connector.json.
-- Application code writes rows here in the SAME transaction as the
-- aggregate save, replacing the old direct KafkaTemplate.send() call.
-- ============================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL, -- target Kafka topic, e.g. 'identity.account.registered'
    aggregate_id    VARCHAR(255) NOT NULL, -- accountId — used as the Kafka message key
    event_type      VARCHAR(255) NOT NULL, -- simple event class name, e.g. 'AccountRegisteredEvent'
    payload         JSONB        NOT NULL, -- serialized EventEnvelope JSON (matches prior Kafka message body)
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_created_at ON outbox_events (created_at);
