# Agent Reliability Lab

**Don't just build agents. Make them reliable.**

Agent Reliability Lab (ARL) is a Java/Spring research application for building
deterministic reliability controls around LLM agents. The core idea is simple:
LLMs may propose actions, but application-owned systems validate, bound, trace
and execute them.

The current app is a local commerce reliability lab backed by synthetic product,
inventory and order fixtures. It supports read-only agent tools plus protected,
confirmation-gated inventory reservations. It uses Alibaba `ReactAgent` for the
reasoning loop and Spring AI OpenAI-compatible clients for DeepSeek and Grok.

## What It Does

- Exposes a loopback HTTP API for agent chat, health, provider status and traces.
- Lets a model call read-only commerce tools: `get_product`, `get_inventory` and `get_order`.
- Keeps baseline `reserve_inventory` proposal-only, while protected mode creates a confirmation token that must be consumed through `/agent/confirm` before stock changes.
- Returns structured tool results instead of trusting model narrative as business truth.
- Records bounded in-memory traces for model calls, tool proposals, tool execution and errors.
- Selects `deepseek` or `grok` per request with explicit errors and no provider fallback.
- Runs deterministic offline evaluation scenarios with JSON metrics.
- Includes a React demo UI for chat, protected confirmations, provider status and traces.

## What It Is Not

ARL is not a production commerce system. M4 has no authentication, durable trace store,
Docker packaging or live evaluation dashboard. Those are later milestones.

For current milestone status and limitations, see [project status](docs/project-status.md).

## Tech Stack

- Java 27 and Maven Wrapper
- Spring Boot 4.0.8
- Spring AI 2.0.0-M1 OpenAI-compatible chat adapter
- Alibaba Agent Framework 2.0.0-M1.1 `ReactAgent`
- JUnit, Spotless, Checkstyle and JaCoCo through the Maven reactor
- Offline evaluation module with scripted-model scenarios
- Vite React TypeScript frontend
- Python requirement-evidence gate in `scripts/verify.py`

Configuration lives in `arl-server/src/main/resources/application.yml` and reads secrets
from environment variables. Runtime and verification details are in
[requirements](docs/requirements.md).

## Repository Layout

- `arl-core`: provider registry, ReactAgent factory, agent execution service, tools,
  domain fixtures and trace store.
- `arl-server`: Spring Boot composition root and HTTP controllers.
- `arl-evaluation`: deterministic offline evaluation runner and report generation.
- `frontend`: Vite React TypeScript demo UI; API client only, no authorization logic.
- `config/requirements.json`: automated requirement-to-test mappings.
- `docs/`: architecture, compatibility, status, requirements, quality and review notes.
- `scripts/`: start script and quality gate tooling.

## Run Locally

```sh
make start
```

The server binds to `http://127.0.0.1:8080` by default. Startup, health and provider
status do not call an LLM. Chat requests require a configured provider key and may incur
provider charges.

Example requests:

```sh
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/providers
curl http://127.0.0.1:8080/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"Check inventory for SKU-1001","provider":"deepseek","mode":"BASELINE"}'
curl http://127.0.0.1:8080/traces/TRACE_ID_FROM_RESPONSE
```

Protected reservation flow:

```sh
curl http://127.0.0.1:8080/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"Reserve 3 units of SKU-1001","provider":"deepseek","mode":"PROTECTED"}'

curl http://127.0.0.1:8080/agent/confirm -H 'Content-Type: application/json' \
  -d '{"confirmationId":"CONFIRMATION_ID_FROM_CHAT","idempotencyKey":"client-key-12345"}'
```

Provider setup is environment-based:

```sh
export ARL_DEFAULT_PROVIDER=deepseek
export DEEPSEEK_API_KEY='your-deepseek-key'
make start
```

Use `XAI_API_KEY` and `ARL_DEFAULT_PROVIDER=grok` for Grok. See
[requirements](docs/requirements.md) for all supported variables.

Set `ARL_DB_PATH=/path/to/arl.db` to preserve inventory and idempotency records across restarts. Leave it blank for an in-memory database.

## Verify

```sh
make verify
```

This is the canonical local/CI gate. It runs Java checks and tests, coverage thresholds,
architecture checks and requirement-evidence validation. Use `make format` to apply Java
formatting.

Default tests use scripted models and loopback HTTP stubs, not paid provider APIs. Live
provider smoke tests are opt-in with `make live-test` after exporting credentials.

Run offline evaluation separately when you want the scenario report:

```sh
make evaluate
```

The report is written to `target/evaluation/report.json`.

Run the frontend after starting the backend:

```sh
make frontend-install
make frontend-dev
```

The Vite dev server proxies API calls to `http://127.0.0.1:8080`.

## Documentation

- [Project status](docs/project-status.md)
- [Requirements and configuration](docs/requirements.md)
- [Architecture](docs/architecture.md)
- [M1 implementation details](docs/m1-implementation.md)
- [Quality harness](docs/quality-harness.md)
- [Framework compatibility](docs/framework-compatibility.md)
- [Code review protocol](docs/code-review.md)
