# ftx-reservations-api

> Reservations domain API: availability, reservations, cancellation and payment results

Part of **Futbolix**, the distributed system for reserving the four football courts of a single
sports facility. Governance and documentation live in
[`ftx-docs`](https://github.com/code-corhuila/ftx-docs). User stories: HU-RES-001 (availability),
HU-RES-002 (create), HU-RES-004 (look up), HU-RES-005 (payment result), HU-RES-006 (outbox),
HU-RES-007 (cancel). Contract: `ftx-docs/07-api/contracts/openapi/reservation-service.yaml`.
Schema: [`ftx-reservations-db`](https://github.com/code-corhuila/ftx-reservations-db).

## Endpoints

| Method and path | Who | Responses |
|---|---|---|
| `GET /api/v1/availability?courtId=&date=` | Public | 200 `Availability`, 400, 404 `COURT_NOT_FOUND`, 503 |
| `POST /api/v1/reservations` + `Idempotency-Key` | Public | 201 `Reservation` (`PENDING`), 400, 404 `COURT_NOT_FOUND`, 409 `RESERVATION_SLOT_UNAVAILABLE`, 422 `BUSINESS_RULE_VIOLATION` / `IDEMPOTENCY_KEY_REUSED`, 503 |
| `GET /api/v1/reservations/{code}` | Public (the code is the secret) | 200, 404 generic |
| `POST /api/v1/reservations/{code}/cancellation` | Public | 200 `CANCELLED`, 404, 409 `RESERVATION_NOT_CANCELLABLE` |
| `GET /api/v1/admin/reservations?page=&limit=&status=&courtId=&dateFrom=&dateTo=` | `ADMINISTRATOR` | 200 `{data, meta}`, 400, 401, 403 |
| `POST /api/v1/admin/reservations/{id}/cancellation` | `ADMINISTRATOR` | 200, 401, 403, 404, 409 |
| `GET /api/v1/health` | Public | 200 `{status, timestamp, dependencies: {database, broker}}`; 503 without database |
| `GET /health`, `/health/liveness`, `/health/readiness` | Probes | 200 `UP` |

Errors use `ErrorResponse` of `_shared.yaml`, with the request's `X-Correlation-Id` as `traceId`.

## Rules this service guarantees

| Rule | How |
|---|---|
| **No double booking** (BR-005) | The database exclusion constraint `reservations_no_double_booking` of `ftx-reservations-db`, not Java code. SQLSTATE `23P01` becomes 409. An integration test fires 8 concurrent requests at the same slot and checks that exactly one survives |
| **The client never sets the price** (FR-008) | The body has no amount (`additionalProperties: false`: an `amount` field is a 400). Amount = hourly price × hours, from `ftx-schedules-api`, which reads it from `ftx-courts-api` |
| **No `CONFIRMED` without the payment provider** (BR-008, M8) | No REST operation confirms or rejects. Only `PaymentApproved` / `PaymentRejected` from `futbolix.events` do, and only on a `PENDING` reservation |
| **Idempotent creation** | `Idempotency-Key` (UUID) is required. Same key and same body: the original reservation. Same key, other body: 422. Key, reservation and event are written in one transaction |
| **Past start times** | Refused with 422, in business time `America/Bogota`, to the minute (as `ftx-front` does) |
| **Inside the schedule, active court** | Every hour of the range must be a bookable slot of the court that day, and the court must be `ACTIVE` (422 otherwise) |
| **The code is a secret** (ADR-007) | `SecureRandom`, 10 characters without look-alikes (32^10 combinations). Unknown and malformed codes get the same 404. Never logged. Responses carry `Cache-Control: no-store`. No public list or search |
| **Administrator routes** | The service checks the RS256 token itself against the JWKS of `ftx-customers-api` (issuer, expiry, `ADMINISTRATOR` role), like `ftx-courts-api`. `X-User-Id` / `X-User-Role` are ignored. Tests cover no token, other role, expired, forged and foreign tokens and spoofed headers |
| **Events are never lost** (ADR-006) | Transactional outbox: `ReservationCreated` / `ReservationCancelled` are written in the same transaction as the reservation. `OutboxRelay` publishes them to `futbolix.events` with publisher confirms; if RabbitMQ is down they wait and go out when it returns |
| **Payment events are applied once** | The `eventId` is stored in `processed_event` in the same transaction as the status change. A failing message goes to `reservation.payment-results.dlq` after 3 attempts, never loops |
| **Fail fast** | `ftx-schedules-api` has a 2 s timeout; without it, availability and creation answer 503. Look-up and cancellation keep working |

## Events

| Direction | Event | Exchange / routing key |
|---|---|---|
| Publishes | `ReservationCreated` (no phone in the payload) | `futbolix.events` / `reservation.created` |
| Publishes | `ReservationCancelled` (`cancelledBy`) | `futbolix.events` / `reservation.cancelled` |
| Consumes | `PaymentApproved` → `CONFIRMED` | queue `reservation.payment-results` ← `payment.approved` |
| Consumes | `PaymentRejected` → `REJECTED` (frees the slot) | queue `reservation.payment-results` ← `payment.rejected` |

Envelope of `02-domain/domain-events.md`: `eventId`, `eventType`, `aggregateId`, `aggregateType`,
`occurredAt`, `version`, `payload`. The AMQP `messageId` is the `eventId`.

## Architecture (hexagonal)

```
domain/                  Reservation, TimeRange, BookableDay, ReservationCode: rules only, no framework
application/             ReservationService (use cases) and its ports
  port/in/               ReservationUseCases, commands, queries, Availability, PaymentResult
  port/out/              ReservationRepository, BookableSlotsCatalog, EventOutbox, Transactions
adapter/in/web/          controllers, ErrorResponse, administrator guard, correlation id, health
adapter/in/messaging/    PaymentEventsListener (RabbitMQ consumer)
adapter/out/persistence/ JdbcReservationRepository, JdbcEventOutbox (Spring JDBC, reservations schema)
adapter/out/schedules/   RestBookableSlotsCatalog (client of ftx-schedules-api)
adapter/out/messaging/   topology of futbolix.events and OutboxRelay
adapter/config/          wiring, business clock (America/Bogota), transactions
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `RESERVATIONS_DB_URL` | `jdbc:postgresql://postgres:5432/ftx` | The single instance of `ftx-infra-postgres` |
| `RESERVATIONS_DB_USER` | `reservations_app` | Login user created by `ftx-infra-postgres` |
| `RESERVATIONS_DB_PASSWORD` | none (required) | Its password, from the env file (never versioned) |
| `SCHEDULES_API_URL` | `http://ftx-schedules-api:8085` | Bookable slots, court status and price |
| `SCHEDULES_API_TIMEOUT` | `2s` | Connect and read timeout |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `rabbitmq` / `futbolix` / none | Broker of `futbolix.events` |
| `RESERVATIONS_MESSAGING_ENABLED` | `true` | `false`: no publisher, no consumer; events wait in the outbox |
| `RESERVATIONS_AUTH_JWKS_URL` | `http://ftx-customers-api:8081/api/v1/auth/jwks` | Public keys of the administrator tokens |
| `RESERVATIONS_AUTH_ISSUER` | `ftx-customers-api` | Required `iss` |
| `PORT` | `8083` | HTTP port |

The service never runs migrations. Logs are JSON (ECS) with the `correlationId` of each request,
and never contain reservation codes, names, emails or phones.

## Run it

The whole platform (database, broker, every API and the gateway) starts from
[`ftx-workflow`](https://github.com/code-corhuila/ftx-workflow). Alone, with Docker:

```bash
docker build -t ftx-reservations-api .
docker run --rm --network platform -p 8083:8083 -e RESERVATIONS_DB_PASSWORD=<password> \
  -e RABBITMQ_PASSWORD=<password> ftx-reservations-api
```

## Tests

```bash
mvn verify                       # or, on Windows without Java: run-tests.bat (Docker only)
```

- **Domain and use cases** (no Spring): lifecycle, amount, whole hours, idempotency, past times,
  schedule, inactive court, unavailable dependency, payment results applied once.
- **Web** (`@WebMvcTest`): every response of the contract, an `amount` sent by the client
  refused, and the **negative authorization tests** of `/admin/**`.
- **Integration** against PostgreSQL with the **real migrations** of `../ftx-reservations-db`, as
  `reservations_app`: 8 concurrent requests for one slot → exactly one reservation; cancelled
  frees the slot; reservation + key + event in one transaction; no phone in the event; an event
  processed once; the application user cannot `DELETE` history. Uses `FTX_IT_JDBC_URL` /
  `FTX_IT_ADMIN_USER` / `FTX_IT_ADMIN_PASSWORD`, or Testcontainers when Docker is available.

The `ci` workflow runs `mvn verify`, gitleaks over the whole history and the image build.

## Open points

- **Cancellation policy** (minimum notice, 422 `CANCELLATION_NOT_ALLOWED`): not defined in
  `ftx-docs` yet, so every `PENDING` or `CONFIRMED` reservation can be cancelled.
- **Approved after cancelled** (ADR-005): the event is ignored and logged; the refund belongs to
  the payment flow when it consumes `ReservationCancelled`.
- **Payment service**: no repository owns it yet (ADR-009). Until it exists, reservations stay
  `PENDING`. Nothing simulates an approval: a reservation is never `CONFIRMED` without evidence
  from the payment provider (M8).

## Branching

`develop`, `qa` and `main` accept no direct commits. Per user story: `hu-<id>-dev → develop`, then
`git cherry-pick -x` into `hu-<id>-qa → qa`, then into a release branch `→ main` with the
instructor's approval and the tag `v3.0.0`.
