# Stadium Ticketing Platform

An event-driven stadium ticket booking platform: browse matches, hold seats, pay, and get
confirmed — built as seven Spring Boot microservices behind a reactive gateway, with a React
storefront.

The interesting part of this codebase is not the CRUD; it is what happens when a step fails
halfway. Seats are held under a distributed lock, payment runs as a saga with compensations, and
every cross-service event goes out through a transactional outbox read by Debezium — so a broker
hiccup between "money taken" and "booking confirmed" cannot lose the event.

---

## Stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 25, Spring Boot 4.1, Spring Cloud Gateway 5 |
| Persistence | PostgreSQL 16 (one database per service), Flyway migrations |
| Cache / locks | Redis 7 via Redisson |
| Messaging | Kafka + Kafka Connect with Debezium CDC (outbox pattern) |
| Search | Elasticsearch 9.5 (match catalog read model) |
| Frontend | React 19, Vite, TypeScript, Tailwind, React Router, React Hook Form + Zod |
| Observability | Metrics: Actuator + Micrometer → Prometheus → Grafana. Logs: Spring Boot ECS JSON → Logstash → Elasticsearch → Kibana |
| Testing | JUnit 5, Mockito, Testcontainers, Embedded Kafka |

---

## Services

| Service | Port | Responsibility |
|---|---|---|
| `api-gateway` | 8080 | Single entry point. Validates JWTs, strips spoofed identity headers, enforces per-route rate limits. Purely technical — no domain model. |
| `identity-service` | 8081 | Registration, email verification, login, refresh-token rotation with reuse detection, password reset. |
| `match-catalog-service` | 8082 | Matches and showtimes. CQRS: Postgres for writes, Elasticsearch for browse/search. **Runs as 2 instances.** |
| `ticket-inventory-service` | 8083 | Seat availability and holds, guarded by a Redisson lock so two customers cannot take the same seat. **Runs as 2 instances.** |
| `payment-service` | 8084 | Payment initiation, Stripe webhooks, refunds, and reconciliation of payments that were charged but never confirmed. |
| `notification-service` | 8085 | Consumes domain events and sends transactional email through Brevo; keeps an in-app notification feed. |
| `booking-service` | 8086 | Orchestrates the booking saga across inventory and payment, with compensations and reconciliation jobs. |
| `frontend` | 5173 | React storefront. |

`common` is a shared library, not a service: JWT filter, correlation-id filter, the RFC 7807
exception handler, `KafkaTopics`, the ShedLock configuration, and the transactional outbox — its
entity, repository, purge job and the publisher base class every service extends with nothing but
its own event-to-topic mapping.

### The replicated services

`ticket-inventory-service` and `match-catalog-service` each run as two instances behind an nginx
load balancer (`infra/nginx/ticket-inventory-lb.conf`, `infra/nginx/catalog-lb.conf`); the other
five run as one. The point is not throughput on a laptop, and the two are replicated for different
reasons.

**ticket-inventory-service** — its correctness rests on machinery a lone process cannot exercise:

| Mechanism | Why one instance proves nothing |
|---|---|
| Redisson seat lock | A JVM-local lock and a Redis-backed one behave identically until a second process competes for the same seat. |
| ShedLock on the reconcilers and outbox cleanup | The `shedlock` row only ever has one claimant, so the guard never has to reject anyone. |
| Kafka consumer-group rebalancing | A group of one is never rebalanced. |

**match-catalog-service** — nothing here is unsafe across instances; what a second one exposes is
the price of its local cache tier. `CachingMatchRepository.save()` invalidates Redis and the
writing pod's own Guava cache, but cannot reach into the other pod's, so an admin publish or cancel
stays visible-as-stale on the other instance until its 5s TTL expires. That window exists on any
multi-pod deployment and is invisible on one. `availableSeats` is unaffected: `overlayLiveSeats`
re-reads it from the live seat counter on every return and never takes it from either Match cache
tier. (That read still passes through `AdaptiveTtlLiveSeatStore`, whose own 1s local TTL is
dropped the moment this pod writes the counter and is bypassed entirely once a count falls below
the scarcity threshold.)

`booking-service` deliberately stays single. It has the same ShedLock and consumer-group machinery,
but its contested path — the Redis idempotency claim on `POST /bookings` — sits behind the
`bookings.idempotency_key` unique index, which rejects a duplicate however many instances are
running. A second booking instance would verify a guarantee that already holds.

Callers address a load balancer (`ticket-inventory-lb:8083`, `catalog-lb:8082`), never an instance.
Each instance is *also* published directly on the host, for when you need to ask one specific
instance a question while debugging. Those host ports are
`<instance number><the service's own port>` — 18083/28083 for inventory, 18082/28082 for the
catalog — so a third instance would be 38083 without anyone having to invent a number.

Because there is no service registry, nginx only knows the instances written into its `upstream`
block, and its health checking is passive (an instance drops out *after* failing three requests).
Adding a third instance means one more `server` line, one more compose stanza, and one more
Prometheus target. That manual step is the trade being made: Eureka or Consul would remove it, at
the cost of another always-on container to keep healthy.

Two details in the nginx configs are load-bearing rather than boilerplate. `proxy_next_upstream`
deliberately omits `non_idempotent` in both, so a POST already handed to an instance is never
replayed against the other one — retrying a seat reservation that may have succeeded is how a seat
gets sold twice, and the catalog's admin writes share a path prefix with its reads.

The balancing strategies differ on purpose. Inventory uses `least_conn`, because a reserve call can
sit on the seat lock for seconds while an availability read returns immediately, so in-flight count
is a far better load signal than request share. The catalog uses plain round-robin: nothing there
blocks, and an even split is what makes the local-cache divergence window observable in the first
place.

---

## Architecture notes

**Hexagonal, per service.** `domain` holds the model and invariants and depends on nothing.
`application` holds use cases and declares ports. `adapter/in` and `adapter/out` are the only
places that know about HTTP, Kafka, JPA or Redis. Domain events are raised on the aggregate and
pulled by the application service.

**Transactional outbox + CDC, not direct publishing.** No service calls `KafkaTemplate.send()` in
a business flow. Events are written to an `outbox_events` row in the *same transaction* as the
aggregate change; Debezium tails that table via Postgres logical replication and produces to Kafka.
A crash between the database commit and the broker therefore cannot drop an event. Connector
configs live in `backend/*/infra/debezium/`.

**Saga with compensation.** `booking-service` drives hold → pay → confirm. Each step has a
compensating action, and two reconciliation jobs sweep for bookings left stuck when an event never
arrived.

**The live seat count moves atomically, in Redis first.** `showtimes.available_seats` in Postgres
is the durable record, but what a browse page shows comes from a Redis counter, seeded when the
showtime is created and decremented on every projected sale. That decrement is a single Lua
`EVAL` — read, floor at zero, write — because the obvious alternative (update Postgres, read the
new total back, overwrite the key) is a read-modify-write across two systems: two overlapping
sales both read before either writes, and the slower one restores seats that were already sold.
Postgres follows the script; a failed write hands back exactly what the script deducted, and any
branch where the two disagree rebuilds the counter from the committed row.

**Idempotency everywhere a retry can reach.** Redis-backed idempotency keys on booking and payment
initiation; a `processed_events` table in notification-service; consumers assume redelivery.

**Events that exhaust retries go to `<topic>-dlt`** and are logged as `ALERT:` by a dead-letter
consumer. Nothing replays them automatically — a poison pill would replay identically forever.

---

## Running it

Everything runs in Docker Compose.

```bash
cp .env.example .env      # then fill in the [REQUIRED] values
docker compose up -d
```

`.env.example` documents every variable, which are required, and where to get the third-party
ones. At minimum you need database and Redis passwords, a JWT secret, a Stripe **test** key, and
Brevo API credentials for outgoing email.

Once the stack is healthy:

| | |
|---|---|
| Storefront | http://localhost:5173 |
| API (through the gateway) | http://localhost:8080 |
| Swagger UI (per service) | http://localhost:8081/swagger-ui.html |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Kibana | http://localhost:5601 |
| Kafka Connect | http://localhost:8083/connectors |

Services declare healthchecks against `/actuator/health`, and dependants wait on
`condition: service_healthy` — so `docker compose up` finishing means the platform is actually
ready to serve, not merely that containers launched.

### Metrics

Prometheus scrapes `/actuator/prometheus` on every service (both instances of each replicated
one, each under its own container name) plus four exporters — node, Redis, Postgres and Kafka. Grafana
auto-loads whatever dashboard JSON sits in `infra/grafana/provisioning/dashboards/`.

Kafka is the one thing measured from outside the application. `kafka-exporter` asks the broker for
each consumer group's committed offset and each partition's end offset, so **lag stays visible
while a consumer is down** — precisely when it matters most. The services cannot report this
themselves: each hand-rolls its `consumerFactory` (for the polymorphic `EventEnvelope`
deserializer), which makes Spring Boot's Kafka metrics autoconfiguration back off, since it only
instruments the factory it builds itself.

The *Kafka — Consumer Lag & Partitions* dashboard exists to answer one question: is consumption
keeping up, and if not, is the partition count what's holding it back? Read "Consumers vs lag by
group" against "Partitions per topic" — members above the partition count means consumers are
idling and partitions are the binding constraint; members at or below it with lag still climbing
means the listener itself is the constraint and more partitions would only multiply pressure on
the same bottleneck.

Every topic currently runs at **one partition** (the broker default — `KAFKA_NUM_PARTITIONS` is
deliberately unset), so each service sets `setConcurrency(1)` on its listener factory to match.
Kafka never assigns one partition to two consumers in the same group, so a higher number would
only allocate idle consumers, each holding a broker connection and heartbeating for no work. The
two settings are one decision: raise them together or not at all. Note this caps *consumer*
parallelism only — HTTP traffic still spreads across both instances of the replicated services,
and both write to their outbox freely.

### Logs

Every service logs twice: a human-readable line to stdout (so `docker compose logs` still reads
normally) and the same event as ECS JSON to `/var/log/app/<service>.log` on the shared `app_logs`
volume. Logstash tails those files and indexes them into the `logs-stadium-dev` data stream, which
is what to point a Kibana data view at.

The replicated services write one file *per instance*
(`ticket-inventory-service-1.log`, `match-catalog-service-2.log`, …) — two JVMs appending to one
file would interleave half-written JSON lines and each apply its own rolling policy to it. Both
files of a service carry the same `service.name`, so a Kibana query for it returns both instances
as one stream; `log.file.path` is the field that tells them apart.

The JSON side is Spring Boot's own `logging.structured.format.file=ecs` — no logging appender
library is involved — so `service.name` and the `correlationId` MDC entry are already fields you can
filter on. File logging is switched on by `LOGGING_FILE_NAME` in `docker-compose.yaml` and stays off
when you run a service locally.

**Filtering on one `correlationId` returns the whole request**, across every hop it took:

| Hop | How the ID survives it |
|---|---|
| browser → gateway | `CorrelationIdWebFilter` reuses `X-Correlation-Id` or mints one |
| gateway → service | the filter sets the header on the forwarded request |
| service → service | `CorrelationIdRequestInitializer` on each `RestClient`, so a new downstream call cannot forget it |
| service → Kafka | `OutboxEventPublisher` copies the MDC into `EventEnvelope.traceId`, stored on the outbox row |
| Kafka → consumer | `CorrelationIdRecordInterceptor` puts `traceId` back into the MDC for the record |

Work started by a `@Scheduled` job (the reconcilers, outbox cleanup) has no request to inherit from.
Outbound REST calls then send no header and the callee mints its own; the Kafka interceptor mints
one per record so those lines still group together.

Only the Java services ship logs (nine containers, since two of the seven services are replicated).
Infrastructure containers — Postgres, Redis, Kafka, the two nginx load balancers, and the Elastic
stack itself — stay on `docker compose logs`.

Elasticsearch, Logstash and Kibana are pinned to the **same** version (9.5.1). Kibana refuses to
start against an Elasticsearch on a different minor, so upgrade the three together — along with
`elasticsearch-java` in `backend/pom.xml` and the Testcontainers image in
`ElasticsearchMatchSearchAdapterIntegrationTest`.

### Elasticsearch accounts

Security is on, and no client uses the `elastic` superuser. `elasticsearch-init` runs once against
the healthy cluster and provisions three scoped accounts (see
`infra/elasticsearch/init-security.sh`); everything that talks to Elasticsearch waits on that
container completing, not merely on the cluster being healthy, because a healthy cluster with no
accounts yet answers 401.

| Account | Used by | Can do |
|---|---|---|
| `kibana_system` | Kibana | built-in role. Kibana rejects `elastic` at config validation — a superuser cannot write the system indices it needs |
| `logstash_internal` | Logstash | append-only (`create_doc`) on `logs-stadium-*`. Cannot read logs back, cannot rewrite or delete what it already shipped |
| `catalog_service` | match-catalog-service | the `matches` index only. No access to the platform's logs |

Passwords come from `.env` (`ELASTIC_PASSWORD`, `KIBANA_SYSTEM_PASSWORD`, `LOGSTASH_PASSWORD`,
`CATALOG_ES_PASSWORD`) — none of them appear in a committed file.

TLS is deliberately **not** enabled: credentials cross `stadium-net` in base64. That is the line
between this local development setup and a production one, and it is the first thing to change if
this ever runs anywhere shared.

### Working on the backend without Docker

```bash
cd backend
./mvnw verify                          # full reactor: build + all tests
./mvnw verify -pl booking-service -am  # a single module (-am matters, see below)
```

Always pass `-am` when building one module. Without it Maven resolves `common` from your local
`~/.m2` copy, which is whatever was last `install`ed — `verify` never installs, so that copy goes
stale and a service silently builds against an old `common`.

Integration tests use Testcontainers, so a running Docker daemon is required. Infrastructure
(Postgres, Redis, Kafka) can be brought up on its own with
`docker compose up -d postgres-identity redis kafka`.

### Working on the frontend

```bash
cd frontend
npm ci
npm run dev     # Vite dev server on 5173
npm run lint
npm run build   # tsc -b && vite build
```

---

## Tests and CI

`.github/workflows/ci.yml` runs on every push and PR to `main`:

1. **build-and-test** — `./mvnw verify -fae -B` over the whole reactor (~700 tests), uploading
   surefire reports if anything fails.
2. **frontend** — `npm ci`, lint, then `tsc -b && vite build`.
3. **docker-build** — builds an image per service, in a matrix, once the tests pass.

---

## Repository layout

```
backend/
  common/                     shared filters, exception handling, Kafka topics, scheduler locking
  api-gateway/                routing, JWT validation, rate limiting
  identity-service/           auth
  match-catalog-service/      matches & showtimes (Postgres + Elasticsearch)
  ticket-inventory-service/   seats & holds
  payment-service/            payments, webhooks, refunds
  booking-service/            the booking saga
  notification-service/       email & in-app notifications
  */infra/debezium/           outbox connector config, per service
frontend/                     React storefront
infra/
  prometheus/                 scrape config + alerting rules
  grafana/                    dashboards & datasources
  elasticsearch/              cluster settings
  logstash/                   log shipping pipeline + node settings
  kibana/                     server settings
  kafka-connect/              connector registration script
docker-compose.yaml           the whole platform
.env.example                  documented environment contract
```

Each service owns its schema under `src/main/resources/db/migration/`; Flyway runs before
Hibernate validation on startup.
