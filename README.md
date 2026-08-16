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
| Search | Elasticsearch 9 (match catalog read model) |
| Frontend | React 19, Vite, TypeScript, Tailwind, React Router, React Hook Form + Zod |
| Observability | Actuator + Micrometer → Prometheus → Grafana |
| Testing | JUnit 5, Mockito, Testcontainers, Embedded Kafka |

---

## Services

| Service | Port | Responsibility |
|---|---|---|
| `api-gateway` | 8080 | Single entry point. Validates JWTs, strips spoofed identity headers, enforces per-route rate limits. Purely technical — no domain model. |
| `identity-service` | 8081 | Registration, email verification, login, refresh-token rotation with reuse detection, password reset. |
| `match-catalog-service` | 8082 | Matches and showtimes. CQRS: Postgres for writes, Elasticsearch for browse/search. |
| `ticket-inventory-service` | 8083 | Seat availability and holds, guarded by a Redisson lock so two customers cannot take the same seat. |
| `payment-service` | 8084 | Payment initiation, Stripe webhooks, refunds, and reconciliation of payments that were charged but never confirmed. |
| `notification-service` | 8085 | Consumes domain events and sends transactional email through Brevo; keeps an in-app notification feed. |
| `booking-service` | 8086 | Orchestrates the booking saga across inventory and payment, with compensations and reconciliation jobs. |
| `frontend` | 5173 | React storefront. |

`common` is a shared library, not a service: JWT filter, correlation-id filter, the RFC 7807
exception handler, `KafkaTopics`, and the ShedLock configuration.

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
| Kafka Connect | http://localhost:8083/connectors |

Services declare healthchecks against `/actuator/health`, and dependants wait on
`condition: service_healthy` — so `docker compose up` finishing means the platform is actually
ready to serve, not merely that containers launched.

### Working on the backend without Docker

```bash
cd backend
./mvnw verify                       # full reactor: build + all tests
./mvnw verify -pl booking-service   # a single module
```

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

1. **build-and-test** — `./mvnw verify -fae -B` over the whole reactor (~560 tests), uploading
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
  kafka-connect/              connector registration script
docker-compose.yaml           the whole platform
.env.example                  documented environment contract
```

Each service owns its schema under `src/main/resources/db/migration/`; Flyway runs before
Hibernate validation on startup.
