# Project Status

## Current Milestone

M3 is implemented as a local protected reservation slice plus deterministic offline
evaluation. Alibaba `ReactAgent` owns the reasoning loop; ARL owns tool contracts,
deterministic validation, confirmation-gated execution, idempotency, response mapping,
trace capture and evaluation reporting.

Implemented behavior:

- `POST /agent/chat` with message validation, optional provider selection and `BASELINE` or `PROTECTED` mode.
- `POST /agent/confirm` to consume protected reservation confirmations with client idempotency keys.
- `GET /health`, `GET /providers` and `GET /traces/{traceId}`.
- Read-only tools: `get_product`, `get_inventory` and `get_order`.
- Baseline `reserve_inventory` remains proposal-only; protected mode creates a confirmation token and confirmation performs the stock change.
- DeepSeek and Grok provider descriptors, explicit unconfigured-provider errors and no fallback.
- Per-request agent execution with bounded model calls and bounded accepted tool proposals.
- SQLite-backed synthetic commerce state, optional file-backed persistence, and in-memory trace retention for the latest 256 completed traces.
- `arl-evaluation` offline scenario runner with isolated catalog state, scripted models, safety metrics and JSON reports.

## Current Fixtures

- `SKU-1001`: Mechanical Keyboard, USD 7900 cents, 20 units.
- `SKU-1002`: Wireless Mouse, USD 2900 cents, zero units.
- Order `123`: five `SKU-1001` units, status `PROCESSING`.

Products and orders are deterministic. Inventory is mutable only through confirmed protected reservations. Restarting the app resets the catalog only when `ARL_DB_PATH` is blank; a file-backed database preserves stock, confirmations and idempotency records. Restarting always clears in-memory traces.

## Deferred Work

The following are not implemented and should not be claimed without a later approved
milestone:

- Full user-facing confirmation workflow beyond the HTTP confirmation endpoint.
- Ambiguous write failure reconciliation beyond recorded idempotent results.
- Live/provider-quality evaluation suite; current M3 evaluation is intentionally offline and deterministic.
- Evaluation runner, benchmark suite or live quality scoring.
- Frontend dashboard, Docker packaging or production deployment flow.

## Known Limitations

- This is a loopback demo with synthetic data and no authentication.
- Provider startup status reports configuration only; it does not prove account entitlement or model availability.
- Offline tests use scripted models and HTTP stubs; they do not prove live model quality.
- Offline evaluation proves deterministic safety and flow behavior only; it does not benchmark live model accuracy.
- Live provider checks are opt-in and may incur provider charges.
- Java 27 works by local executable evidence, but Spring Boot's documented support currently stops at Java 26.
- Spring AI and Alibaba dependencies are milestone releases.
- DeepSeek thinking is disabled through a provider-specific transport workaround until an upgraded adapter passes equivalent wire tests.

See [M1 implementation details](m1-implementation.md) and [framework compatibility](framework-compatibility.md) for deeper evidence.
