# Debezium outbox connector — ticket-inventory-service

`inventory-outbox-connector.json` configures Debezium's Postgres connector +
outbox event router to tail `outbox_events` in `inventory_db` and produce to
Kafka. Kafka Connect has no declarative-file mode, so the connector must be
registered once (per environment) via its REST API.

## Register (local dev)

```bash
docker compose up -d
curl -X POST -H "Content-Type: application/json" \
  --data @ticket-inventory-service/infra/debezium/inventory-outbox-connector.json \
  http://localhost:8083/connectors
```

## Verify

```bash
curl http://localhost:8083/connectors/inventory-outbox-connector/status
```

## How it routes

- `aggregate_type` in each outbox row holds the exact target topic string
  (e.g. `inventory.seats.sold`, `inventory.seats.reserved`,
  `inventory.seats.released`, from `common`'s `KafkaTopics`).
  `route.topic.replacement=${routedByValue}` makes the produced Kafka topic
  equal to that value — no extra prefix — so future consumers don't need any
  changes.
- `table.expand.json.payload=false` passes the `payload` column through
  unparsed, so the Kafka message body is byte-identical to the
  `EventEnvelope` JSON the old `KafkaTemplate.send()` call used to produce.
- `table.field.event.key=aggregate_id` uses the business aggregate id
  (showtimeId) as the Kafka message key, replacing the old (effectively
  random) `eventId`-keyed messages — same fix as booking-service/identity-service.
  No consumer reads the Kafka record key today, so this is safe.

## Why this exists

`SeatSaleConfirmer#confirmSale` writes the outbox row in the SAME Postgres
transaction as the SOLD status update. Before this connector, the old
`InventoryEventPublisher` called `KafkaTemplate.send()` directly, before that
transaction committed — a crash or failed commit right after the send could
emit a `SeatsSoldEvent` for a sale that was never actually persisted, and a
Kafka hiccup could silently drop the event with no retry. Reserve/release
events don't have a Postgres write to join (see `SeatHoldPort`), but are
written to the same outbox table for a single consistent publish path.

## Note on credentials

This config reuses the same `aireak`/`aireak` superuser the app itself
connects with (consistent with the rest of this repo's local dev
docker-compose, which doesn't split credentials per concern). A production
deployment should use a dedicated least-privilege replication role instead
of a superuser.

## ticket-inventory-service no longer talks to Kafka directly

Unlike booking-service (which still has an inbound `@KafkaListener` for
payment results), ticket-inventory-service doesn't consume Kafka at all —
after this migration it has no `spring-kafka` dependency and no Kafka
producer beans; all outbound events go through this connector.
