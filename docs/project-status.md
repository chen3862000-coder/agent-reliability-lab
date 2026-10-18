# Project Status

## Current Milestone

M1 is implemented as a local, read-only synthetic commerce agent. Alibaba `ReactAgent`
owns the reasoning loop; ARL owns tool contracts, deterministic fixture reads, response
mapping and trace capture.

Implemented behavior:

- `POST /agent/chat` with message validation, optional provider selection and `BASELINE` mode.
- `GET /health`, `GET /providers` and `GET /traces/{traceId}`.
- Read-only tools: `get_product`, `get_inventory` and `get_order`.
- Proposal-only `reserve_inventory`, returning `RESERVATION_NOT_ENABLED` without changing stock.
- DeepSeek and Grok provider descriptors, explicit unconfigured-provider errors and no fallback.
- Per-request agent execution with bounded model calls and bounded accepted tool proposals.
- In-memory trace retention for the latest 256 completed traces.

## Current Fixtures

- `SKU-1001`: Mechanical Keyboard, USD 7900 cents, 20 units.
- `SKU-1002`: Wireless Mouse, USD 2900 cents, zero units.
- Order `123`: five `SKU-1001` units, status `PROCESSING`.

Fixtures are immutable and deterministic. Restarting the app clears traces, not fixture values.

## Deferred Work

The following are not implemented and should not be claimed without a later approved
milestone:

- Executable inventory reservations or commerce writes.
- Protected mode, user confirmation and exact-argument authorization.
- SQLite persistence, transactional idempotency or write recovery.
- Evaluation runner, benchmark suite or live quality scoring.
- Frontend dashboard, Docker packaging or production deployment flow.

## Known Limitations

- This is a loopback demo with synthetic data and no authentication.
- Provider startup status reports configuration only; it does not prove account entitlement or model availability.
- Offline tests use scripted models and HTTP stubs; they do not prove live model quality.
- Live provider checks are opt-in and may incur provider charges.
- Java 27 works by local executable evidence, but Spring Boot's documented support currently stops at Java 26.
- Spring AI and Alibaba dependencies are milestone releases.
- DeepSeek thinking is disabled through a provider-specific transport workaround until an upgraded adapter passes equivalent wire tests.

See [M1 implementation details](m1-implementation.md) and [framework compatibility](framework-compatibility.md) for deeper evidence.
