# Project Status

## Current Capabilities

The current implementation is a local protected reservation demo with deterministic
offline evaluation and a React demo UI. Alibaba `ReactAgent` owns the reasoning loop; ARL
owns tool contracts, deterministic validation, confirmation-gated execution, idempotency,
response mapping, trace capture, evaluation reporting and backend safety decisions.

Implemented behavior:

- `POST /agent/chat` with message validation, optional provider selection and `BASELINE` or `PROTECTED` mode.
- `POST /agent/confirm` to consume protected reservation confirmations with client idempotency keys.
- `GET /health`, `GET /providers` and `GET /traces/{traceId}`.
- Read-only tools: `get_product`, `get_inventory` and `get_order`.
- Baseline `reserve_inventory` remains proposal-only; protected mode creates a confirmation token and confirmation performs the stock change.
- DeepSeek and Grok provider descriptors, explicit unconfigured-provider errors and no fallback.
- Per-request agent execution with bounded model calls and bounded accepted tool proposals.
- SQLite-backed synthetic commerce state, optional file-backed persistence, and in-memory trace retention for the latest 256 completed traces.
- `arl-evaluation` offline runner with 59 deterministic cases across 10 reliability categories, isolated catalog state, scripted models, explicit routing-contract/safety/idempotency/recovery/trace metrics and JSON reports.
- `frontend` Vite React UI for providers, chat, protected reservation confirmation and trace viewing.
- Docker Compose local demo stack with health checks and persistent SQLite volume.
- Docker Compose build and startup are covered by the local demo workflow.
- `SafeToolExecutor` and `ReservationPolicy` backend boundary for reservation proposals and confirmation requests.
- Bounded retry with deadline/jitter for classified SQLite BUSY reads; model calls and write side effects remain non-retried.

## Current Fixtures

- `SKU-1001`: Mechanical Keyboard, USD 7900 cents, 20 units.
- `SKU-1002`: Wireless Mouse, USD 2900 cents, zero units.
- Order `123`: five `SKU-1001` units, status `PROCESSING`.

Products and orders are deterministic. Inventory is mutable only through confirmed protected reservations. Restarting the app resets the catalog only when `ARL_DB_PATH` is blank; a file-backed database preserves stock, confirmations and idempotency records. Restarting always clears in-memory traces.

## Out of Scope

The following are not implemented and should not be claimed without new implementation and
verification:

- Live/provider-quality evaluation suite; current evaluation is intentionally offline and deterministic.
- Live quality scoring.
- Production deployment, durable trace/audit storage, and identity-bound authorization.

## Known Limitations

- This is a loopback demo with synthetic data and no authentication.
- The frontend is an API client only; backend remains the safety and authorization boundary.
- Reservation confirmation is a demo safety control only: it is not bound to user identity/session and is not production authorization or a complete HITL policy system.
- SQLite concurrency is tested across separate connections to one local database file, not across hosts or production multi-instance infrastructure.
- SQLite read retries are bounded and apply only to classified BUSY errors; writes are not blindly retried.
- A busy confirmation write returns `WRITE_BUSY_RETRY_SAME_KEY`; clients should retry using the same confirmation ID and idempotency key.
- Provider startup status reports configuration only; it does not prove account entitlement or model availability.
- Offline tests use scripted models and HTTP stubs; they do not prove live model quality.
- Offline evaluation proves deterministic safety and flow behavior only; it does not benchmark live model accuracy.
- Live provider checks are opt-in and may incur provider charges.
- Java 27 works by local executable evidence, but Spring Boot's documented support currently stops at Java 26.
- Spring AI and Alibaba dependencies are milestone releases.
- DeepSeek thinking is disabled through a provider-specific transport workaround until an upgraded adapter passes equivalent wire tests.

See [framework compatibility notes](engineering/framework-compatibility.md) for deeper
dependency evidence.
