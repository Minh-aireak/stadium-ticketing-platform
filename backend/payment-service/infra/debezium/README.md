# Debezium outbox connector — payment-service

`payment-outbox-connector.json` configures Debezium's Postgres connector +
outbox event router to tail `outbox_events` in `payment_db` and produce to
Kafka. Kafka Connect has no declarative-file mode, so the connector must be
registered once (per environment) via its REST API.

## Prerequisite

`postgres-payment` must run with `wal_level=logical` (set in the root
`docker-compose.yaml`) — Debezium reads the outbox table via Postgres logical
replication.

## Register (local dev)

```bash
docker compose up -d
curl -X POST -H "Content-Type: application/json" \
  --data @payment-service/infra/debezium/payment-outbox-connector.json \
  http://localhost:8083/connectors
```

## Verify

```bash
curl http://localhost:8083/connectors/payment-outbox-connector/status
```

## How it routes

- `aggregate_type` in each outbox row holds the exact target topic string
  (e.g. `payment.payment.succeeded`, `payment.payment.failed`, from
  `common`'s `KafkaTopics`). `route.topic.replacement=${routedByValue}` makes
  the produced Kafka topic equal to that value — no extra prefix — so
  consumers (booking-service's `PaymentResultConsumer`) don't need any
  changes.
- `table.expand.json.payload=false` passes the `payload` column through
  unparsed, so the Kafka message body is byte-identical to the
  `EventEnvelope` JSON the old `KafkaTemplate.send()` call used to produce.
- `table.field.event.key=aggregate_id` uses the business aggregate id
  (paymentId) as the Kafka message key, replacing the old (effectively
  random) `eventId`-keyed messages — same fix as booking/identity/inventory.
  No consumer reads the Kafka record key today, so this is safe.

## Note on credentials

This config reuses the same `aireak`/`aireak` superuser the app itself
connects with (consistent with the rest of this repo's local dev
docker-compose, which doesn't split credentials per concern). A production
deployment should use a dedicated least-privilege replication role instead
of a superuser.

## payment-service has no inbound Kafka consumer

Unlike booking-service, payment-service doesn't listen to any Kafka topic —
it only produces `PaymentInitiatedEvent`/`PaymentSucceededEvent`/
`PaymentFailedEvent`. This migration removes `spring-kafka` from the module
entirely (no producer or consumer beans remain); the old dual-write risk
(`KafkaTemplate.send()` racing the Postgres transaction commit in
`PaymentSagaSteps`) is eliminated by construction.
