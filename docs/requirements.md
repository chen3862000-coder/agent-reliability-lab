# Requirements and Configuration

## Toolchain

- OpenJDK 27; the Maven reactor compiles with `java.version=27`.
- Maven Wrapper 3.3.4 downloads Maven 3.9.11; use `./mvnw`, not a global Maven.
- Node.js 24 and npm 11 are used for the frontend; `package-lock.json` pins the resolved dependency graph.
- Python 3.9+ is required for the canonical quality gate.
- First dependency resolution needs internet access. Cached Java verification can use `./mvnw -o clean verify`.
- Normal tests require loopback socket access for HTTP adapter stubs.

## Commands

```sh
make verify       # canonical local/CI gate
make format       # apply Java formatting
make start        # build and start arl-server on 127.0.0.1:8080 by default
make run          # alias for make start
make evaluate     # run deterministic offline evaluation and write target/evaluation/report.json
make frontend-install # install frontend dependencies
make frontend-dev # start Vite frontend dev server on 127.0.0.1:5173
make frontend-verify # run frontend tests and production build
make live-test    # opt-in paid provider smoke tests
```

Focused Java commands:

```sh
./mvnw clean verify
./mvnw -pl arl-core test
./mvnw -pl arl-server -am test
./mvnw -pl arl-core -Dtest=ProviderTransportTest test
./mvnw -pl arl-evaluation -am test
```

## Application Configuration

Runtime defaults are in `arl-server/src/main/resources/application.yml`. Secrets should be
supplied through environment variables rather than committed files. ARL does not
automatically load `.env`; export variables in the shell that starts the app.

| Variable | Default / meaning |
|---|---|
| `ARL_PORT` | `8080`; HTTP port, bound to `127.0.0.1` by default |
| `ARL_DB_PATH` | Blank means in-memory SQLite reset on restart; set a file path to persist stock, confirmations and idempotency records |
| `ARL_DEFAULT_PROVIDER` | `deepseek`; valid values are `deepseek` or `grok` |
| `DEEPSEEK_API_KEY` | Optional DeepSeek key; absent means `NOT_CONFIGURED` |
| `DEEPSEEK_MODEL` | `deepseek-flash` |
| `XAI_API_KEY` | Optional xAI/Grok key; absent means `NOT_CONFIGURED` |
| `GROK_MODEL` | `grok-4.7` |
| `ARL_LLM_TIMEOUT_SECONDS` | `30`; connect/read timeout, range 1-300 |
| `ARL_LLM_TEMPERATURE` | Optional DeepSeek temperature, range 0-2; never sent to Grok |

DeepSeek example:

```sh
export ARL_DEFAULT_PROVIDER=deepseek
export DEEPSEEK_API_KEY='your-deepseek-key'
make start
```

Grok example:

```sh
export ARL_DEFAULT_PROVIDER=grok
export XAI_API_KEY='your-xai-key'
make start
```

Persistent local catalog example:

```sh
export ARL_DB_PATH="$PWD/target/local/arl.db"
make start
```

The schema is created automatically and seed products/orders are inserted with
`insert or ignore`, so existing stock and idempotency records are preserved when the same
database file is reused.

## Reservation Confirmation

`reserve_inventory` in `BASELINE` mode validates a proposal but does not mutate stock.
`PROTECTED` mode returns confirmation metadata in the tool result. Confirm it separately:

```sh
curl http://127.0.0.1:8080/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"Reserve 3 units of SKU-1001","mode":"PROTECTED"}'

curl http://127.0.0.1:8080/agent/confirm -H 'Content-Type: application/json' \
  -d '{"confirmationId":"CONFIRMATION_ID_FROM_CHAT","idempotencyKey":"client-key-12345"}'
```

The idempotency key must be client-generated and stable for retries of the same
confirmation. Reusing a key for a different confirmation returns `IDEMPOTENCY_KEY_REUSED`.
This is a confirmation-gated local demo flow, not identity-bound authorization or a complete
HITL policy system: confirmations are not associated with an authenticated user/session.

## Provider Behavior

- DeepSeek uses `https://api.deepseek.com/chat/completions`.
- Grok uses `https://api.x.ai/v1/chat/completions`.
- There is no automatic fallback between providers.
- Missing credentials keep a provider unconfigured; clients are not constructed for absent keys.
- Each agent request gets fresh execution state; checkpoint/thread IDs are not shared.
- Output is capped at 1,024 tokens, model calls at four and SDK retries are disabled.
- SQLite reads retry only classified BUSY failures, with at most three attempts, exponential delay plus jitter, and a 2-second retry deadline. If a confirmation write cannot acquire SQLite's write lock within its 100ms busy timeout, the API returns HTTP 503 `WRITE_BUSY_RETRY_SAME_KEY`; retry only with the exact same confirmation ID and idempotency key. Writes are not blindly repeated under a new key.

## Verification Notes

`make verify` runs Python gate tests, Maven clean verification, formatting checks,
Checkstyle maintainability checks, PMD CPD duplicate-code detection, JUnit, JaCoCo
coverage gates, architecture checks and fresh requirement evidence generation.

Generated evidence is written to `target/quality/requirements-report.json`. The historical
`docs/m0-verification.json` is not evidence for current source state.

Live tests require both explicit opt-in and provider credentials:

```sh
make live-test
```

If a provider key is absent, that provider's live test is skipped and should be treated as
`NOT_RUN`, not successful live verification. DeepSeek and Grok live verification remains
pending until run with real credentials and recorded evidence.

## Offline Evaluation

`make evaluate` runs the offline suite with scripted models and isolated SQLite catalog
state per case. It currently has 59 deterministic cases across 10 reliability categories and writes
`target/evaluation/report.json` with:

- total, passed and failed scenario counts
- deterministic routing-contract match: observed tool calls are compared with scenario-declared expectations. Scripted models use the scenario, so this does not measure autonomous model routing quality.
- safety violations: scenarios violating expected stock invariance or confirmed stock result
- idempotency: protected writes passing same-key replay and different-confirmation key-reuse checks; other scenarios are explicitly N/A (`idempotencyApplicable=false`)
- recovery: protected writes returning the stored result on a replay; other scenarios are explicitly N/A (`recoveryApplicable=false`)
- protected-write pass count
- trace completeness: scenarios containing lifecycle events, model outcome, and registered-tool execution/failure; unknown-tool rejection requires a proposal and model failure
- per-scenario status, error code, tool calls, stock delta and trace events

The offline suite is deterministic and does not call paid providers. It is not evidence of
live model accuracy or account entitlement.

## Docker Demo

With Docker Engine and Compose installed, start the full local stack with one command:

```sh
ARL_DEFAULT_PROVIDER=deepseek DEEPSEEK_API_KEY='your-deepseek-key' make demo
```

For a cold, uncached image build before starting the stack, use `make demo-cold`. The
backend Docker build resolves Maven dependencies from POM-only layers and uses a BuildKit
cache for `/root/.m2`. It skips tests and static checks already enforced by `make verify`;
it still compiles and packages the backend. `make demo` uses normal Docker layer caching.
Cold-build verification on Apple Silicon Docker Desktop completed successfully in 163.92s
(about 2m44s), including both images, container startup and health checks. `/health` and
`/providers` were verified through the frontend proxy. This is a local measurement, not a
build-time guarantee for other machines or network conditions.

Open `http://127.0.0.1:8088`. Provider keys are passed at runtime, not baked into images.
Compose persists SQLite in the `arl-data` named volume. Stop with `make demo-down`; this
preserves the volume. This unauthenticated demo is not a production deployment.

## Frontend

The M4 frontend is a Vite React TypeScript app in `frontend/`. It is an API client only:
provider selection, chat, reservation confirmation and trace display all call backend HTTP
endpoints. It must not make provider calls, access SQLite directly or implement trusted
authorization decisions.

Run locally:

```sh
make start
make frontend-install
make frontend-dev
```

The dev server proxies `/agent`, `/health`, `/providers` and `/traces` to
`http://127.0.0.1:8080`.
