# Debezium outbox connector — identity-service

`identity-outbox-connector.json` configures Debezium's Postgres connector +
outbox event router to tail `outbox_events` in `identity_db` and produce to
Kafka. Kafka Connect has no declarative-file mode, so the connector must be
registered once (per environment) via its REST API.

## Register (local dev)

```bash
docker compose up -d
curl -X POST -H "Content-Type: application/json" \
  --data @identity-service/infra/debezium/identity-outbox-connector.json \
  http://localhost:8083/connectors
```

## Verify

```bash
curl http://localhost:8083/connectors/identity-outbox-connector/status
```

## How it routes

- `aggregate_type` in each outbox row holds the exact target topic string
  (e.g. `identity.account.registered`, from `common`'s `KafkaTopics`).
  `route.topic.replacement=${routedByValue}` makes the produced Kafka topic
  equal to that value — no extra prefix — so consumers (e.g.
  notification-service's `AccountEventConsumer`) don't need any changes.
- `table.expand.json.payload=false` passes the `payload` column through
  unparsed, so the Kafka message body is byte-identical to the
  `EventEnvelope` JSON the old `KafkaTemplate.send()` call used to produce.
- `table.field.event.key=aggregate_id` uses the business aggregate id
  (accountId) as the Kafka message key, replacing the old (effectively
  random) `eventId`-keyed messages — see the plan doc for why.

## Note on credentials

This config reuses the same `aireak`/`aireak` superuser the app itself
connects with (consistent with the rest of this repo's local dev
docker-compose, which doesn't split credentials per concern). A production
deployment should use a dedicated least-privilege replication role instead
of a superuser.
