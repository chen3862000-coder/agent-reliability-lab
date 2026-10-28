# Agent Reliability Lab

[![CI: verify](https://img.shields.io/badge/CI-verify-blue)](.github/workflows/verify.yml)
![Java 27](https://img.shields.io/badge/Java-27-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Don't just build agents. Make them reliable.**

Agent Reliability Lab (ARL) is a Java/Spring research application for adding
deterministic reliability controls around LLM agents. Models may propose actions;
application-owned systems validate, bound, trace and execute them.

The local demo uses synthetic commerce data, Alibaba `ReactAgent`, Spring AI
OpenAI-compatible clients for DeepSeek/Grok, SQLite-backed confirmation-gated inventory
reservations, deterministic offline evaluation and a React UI.

```mermaid
flowchart LR
  UI["React demo UI"] --> API["arl-server<br/>HTTP API"]
  API --> AGENT["AgentExecutionService"]
  AGENT --> REACT["Alibaba ReactAgent"]
  REACT --> LLM["DeepSeek / Grok"]
  AGENT --> TOOLS["CommerceToolRegistry<br/>SafeToolExecutor"]
  TOOLS --> CATALOG["CommerceCatalog<br/>SQLite"]
  AGENT --> TRACES["TraceStore<br/>in-memory diagnostics"]
  EVAL["arl-evaluation<br/>scripted scenarios"] --> AGENT
```

*The browser UI is an API client. Safety and write decisions remain in the backend.*

![Agent Reliability Lab browser demo](docs/images/demo-ui.png)

*Initial demo UI state with synthetic data. Provider keys are required before chat can run; see [Security and safe use](#security-and-safe-use).*

## Demo Video

[▶ Watch the 84-second Agent Reliability Lab demo](docs/videos/agent-reliability-lab-demo.mp4)

The walkthrough uses DeepSeek against synthetic commerce data to demonstrate a baseline
inventory query, structured tool results, execution traces and a protected reservation
that requires explicit human confirmation before the application changes stock.

## Features

- HTTP API for agent chat, health, provider status, confirmation and traces.
- Read tools for product, inventory and order data.
- `BASELINE` reservation proposals do not mutate stock; `PROTECTED` proposals require a separate confirmation.
- SQLite transactions and idempotency keys protect the reservation write path.
- Bounded in-memory traces record model and tool events.
- Offline evaluator with 59 deterministic cases across 10 reliability categories.
- React demo for chat, provider status, confirmation and trace inspection.
- Docker Compose local demo with health-gated startup and persistent SQLite volume.

## Scope

ARL is a local demo, not a production commerce service. It has no authentication,
identity-bound confirmation authorization, or durable trace/audit store. Offline cases
check deterministic application contracts; they do not measure autonomous model quality.
Live provider tests are opt-in and are not part of the default verification gate.

## Security and Safe Use

This repository is an unauthenticated local demo. Do not expose the frontend or API to an
untrusted network. Confirmation IDs are not authorization, traces are in-memory
diagnostics, and provider keys may incur charges. See [SECURITY.md](SECURITY.md).

## Run the Demo

Requirements: Docker Engine and Docker Compose for the container demo, or Java 27, Node 24
and npm 11 for running processes locally. Provider keys are optional for startup and
provider-status viewing, but required for chat and may incur charges.

### Containerized

```sh
ARL_DEFAULT_PROVIDER=deepseek DEEPSEEK_API_KEY='your-deepseek-key' make demo
```

Open `http://127.0.0.1:8088`. Compose health-gates frontend startup and stores SQLite in
the `arl-data` named volume. To stop the stack while preserving its data:

```sh
make demo-down
```

Use `make demo-cold` for an uncached image build. Provider keys are runtime environment
variables, never image build arguments.

### Native Local Cluster

Run the Java backend and Vite frontend together on the host:

```sh
make frontend-install   # once
export ARL_DEFAULT_PROVIDER=deepseek
export DEEPSEEK_API_KEY='your-deepseek-key'
# Optional: persist stock/idempotency across restarts
export ARL_DB_PATH="$PWD/target/local/arl.db"

make lab-up
make lab-status
```

Open `http://127.0.0.1:5173`. Logs and PID files are written under `.arl-run/`. Stop the
cluster with `make lab-down`; use `make lab-clean` to also remove its logs.

The native scripts use API port `8080` and UI port `5173`. For Grok, use
`ARL_DEFAULT_PROVIDER=grok` and `XAI_API_KEY`. ARL does not automatically load `.env`;
export values in the shell that starts the cluster. See
[Native local lab cluster](docs/local-lab.md) for configuration, lifecycle and
troubleshooting details.

### Try the Flows

1. Check that the provider panel shows the provider as `CONFIGURED`.
2. Choose the `Inventory` sample prompt and send it in `BASELINE` mode.
3. Choose `Protected reserve`, submit the proposal, then confirm it in the reservation card.
4. Load the trace to inspect ordered model/tool events.

Example API requests:

```sh
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/providers
curl http://127.0.0.1:8080/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"Check inventory for SKU-1001","provider":"deepseek","mode":"BASELINE"}'
```

Expected outcomes: missing keys yield `PROVIDER_NOT_CONFIGURED`; insufficient inventory
does not produce a confirmation; replaying the same confirmation/key returns the prior
result; using that key with another confirmation returns `IDEMPOTENCY_KEY_REUSED`. Blank
`ARL_DB_PATH` resets in-memory stock on restart; file-backed SQLite preserves it.

## Verification

```sh
make verify
```

The canonical gate runs Java checks/tests, coverage and architecture checks, requirement
evidence, frontend tests/build and `npm audit --audit-level=high`. `make format` applies
Java formatting.

Run deterministic offline evaluation separately:

```sh
make evaluate
```

This writes `target/evaluation/report.json`. Its routing-contract metric compares observed
tool execution with scenario-declared expectations; scripted models follow those
expectations, so the metric is not a measure of autonomous model routing quality. Live
provider tests are opt-in with `make live-test` and credentials.

## Project Structure

- `arl-core`: providers, agent orchestration, tools, policy, domain and trace store.
- `arl-server`: Spring Boot composition root and HTTP API.
- `arl-evaluation`: offline scenario runner and JSON reports.
- `frontend`: Vite React TypeScript API client.
- `docs/`: current architecture, status, requirements and engineering notes.

## Documentation

- [Project status](docs/project-status.md)
- [Architecture](docs/architecture.md)
- [Domain-driven system design](docs/domain-driven-design.md)
- [Requirements and configuration](docs/requirements.md)
- [Native local lab cluster](docs/local-lab.md)
- [Quality harness](docs/engineering/quality-harness.md)
- [Code review checklist](docs/engineering/code-review.md)
- [Framework compatibility notes](docs/engineering/framework-compatibility.md)
- [Security policy](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
