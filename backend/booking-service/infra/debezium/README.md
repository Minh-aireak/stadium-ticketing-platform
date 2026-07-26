# Debezium outbox connector — booking-service

`booking-outbox-connector.json` configures Debezium's Postgres connector +
outbox event router to tail `outbox_events` in `booking_db` and produce to
Kafka. Kafka Connect has no declarative-file mode, so the connector must be
registered once (per environment) via its REST API.

## Register (local dev)

```bash
docker compose up -d
curl -X POST -H "Content-Type: application/json" \
  --data @booking-service/infra/debezium/booking-outbox-connector.json \
  http://localhost:8083/connectors
```

## Verify

```bash
curl http://localhost:8083/connectors/booking-outbox-connector/status
```

## How it routes

- `aggregate_type` in each outbox row holds the exact target topic string
  (e.g. `booking.booking.confirmed`, `booking.booking.cancelled`, from
  `common`'s `KafkaTopics`). `route.topic.replacement=${routedByValue}` makes
  the produced Kafka topic equal to that value — no extra prefix — so
  consumers (e.g. notification-service's `BookingEventConsumer`) don't need
  any changes.
- `table.expand.json.payload=false` passes the `payload` column through
  unparsed, so the Kafka message body is byte-identical to the
  `EventEnvelope` JSON the old `KafkaTemplate.send()` call used to produce.
- `table.field.event.key=aggregate_id` uses the business aggregate id
  (bookingId) as the Kafka message key, replacing the old (effectively
  random) `eventId`-keyed messages — same fix as identity-service. No
  consumer reads the Kafka record key today (notification-service's
  `BookingEventConsumer` only reads `EventEnvelope` fields), so this is safe.

## Note on credentials

This config reuses the same `aireak`/`aireak` superuser the app itself
connects with (consistent with the rest of this repo's local dev
docker-compose, which doesn't split credentials per concern). A production
deployment should use a dedicated least-privilege replication role instead
of a superuser.

## booking-service still consumes Kafka directly

Unlike identity-service (pure event producer), booking-service also has an
inbound `@KafkaListener` (`PaymentResultConsumer`, subscribed to
`payment.payment.succeeded` / `payment.payment.failed`) that drives the
booking saga forward. This connector only replaces the *producer* side —
`spring-kafka` and `KafkaConfig`'s consumer beans are unchanged.
