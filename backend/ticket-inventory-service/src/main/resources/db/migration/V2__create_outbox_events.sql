-- ============================================================
-- V2: transactional outbox for ticket-inventory-service domain events
--
-- Debezium tails this table via logical replication (CDC) and
-- routes each row to a Kafka topic using the outbox event router
-- SMT — see ticket-inventory-service/infra/debezium/inventory-outbox-connector.json.
-- Application code writes rows here (SeatsSoldEvent in the same transaction
-- as the seat-inventory save; SeatsReservedEvent/SeatsReleasedEvent don't
-- have a Postgres write to join, so they're written standalone), replacing
-- the old direct KafkaTemplate.send() call.
-- ============================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL, -- target Kafka topic, e.g. 'inventory.seats.sold'
    aggregate_id    VARCHAR(255) NOT NULL, -- showtimeId — used as the Kafka message key
    event_type      VARCHAR(255) NOT NULL, -- simple event class name, e.g. 'SeatsSoldEvent'
    payload         JSONB        NOT NULL, -- serialized EventEnvelope JSON (matches prior Kafka message body)
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_created_at ON outbox_events (created_at);
