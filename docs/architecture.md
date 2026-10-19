# System Architecture

Agent Reliability Lab (ARL) is designed around one rule: **LLMs propose actions;
deterministic systems validate, bound, trace and execute them**. The application uses
Alibaba `ReactAgent` for model interaction and tool selection, while ARL owns provider
configuration, tool contracts, execution boundaries, response mapping and trace evidence.

This document describes the intended final architecture, then calls out what is currently
implemented in M2 and what changes in later milestones.

## Architecture Goals

- Keep model reasoning separate from trusted business execution.
- Make provider choice explicit, observable and non-fallback.
- Isolate every request with a fresh agent graph, hook state and trace.
- Treat provider output as untrusted until validated against tool contracts and domain rules.
- Preserve an auditable path from user request to model call, tool proposal, tool result and final response.
- Add protected writes only behind deterministic authorization, confirmation, transaction and idempotency controls.

## High-Level Architecture

![High-level system architecture](diagrams/system-architecture.png)

ARL is a small layered system:

- **Interface layer**: HTTP clients call Spring MVC endpoints in `arl-server`. Controllers own transport validation and HTTP status mapping, not business execution.
- **Application orchestration layer**: `AgentExecutionService` coordinates one request at a time, resolves provider/model metadata, creates a fresh agent and converts observed tool results into the API response.
- **Agent, provider and tool layer**: Alibaba `ReactAgent` runs the reasoning graph. `ProviderRegistry` supplies immutable DeepSeek/Grok `ChatModel` instances. `CommerceToolRegistry` supplies callback contracts, strict JSON argument validation and deterministic tool results.
- **Domain and reliability boundary**: M2 reads deterministic `CommerceCatalog` fixtures and mutates inventory only through confirmation-gated SQLite transactions with idempotency records. SQLite may run in-memory or file-backed via `ARL_DB_PATH`.

## Modules and Ownership

### `arl-core`

Owns reusable agent and reliability logic:

- `ProviderRegistry`: immutable provider configuration, credential gating, timeout/retry settings and DeepSeek transport workaround.
- `ReactAgentFactory`: fresh Alibaba `ReactAgent` graph plus model-call limit hook per request.
- `AgentExecutionService`: orchestration, model/tool observation, bounded execution, sanitized error mapping and result construction.
- `CommerceToolRegistry`: central tool contracts, schemas, validation, callback creation and deterministic tool execution.
- `CommerceCatalog`: current immutable synthetic commerce data.
- `TraceStore`: bounded in-memory trace snapshots.

`arl-core` must stay independent of Spring Boot composition and must not depend on `arl-server`.

### `arl-server`

Owns runtime composition and HTTP transport:

- `ArlApplication`: Spring Boot entrypoint, bean wiring and environment/YAML-backed provider settings.
- `AgentController`: `/agent/chat`, `/health`, `/providers` and `/traces/{traceId}` endpoints.
- `ApiErrors`: sanitized routing and framework error responses.

`arl-server` depends on `arl-core`; dependency direction does not reverse.

### Future Modules

- `arl-evaluation`: independent offline runner for isolated fixtures, scripted-model fault injection, metrics and JSON reports. It does not run during normal application startup.
- `frontend` (planned M4): React/TypeScript client of server APIs. It must not own authorization decisions.

Create new modules only when they contain executable functionality.

## Main Agent Flow

![Main agent flow sequence](diagrams/agent-flow-sequence.png)

Main request flow for `POST /agent/chat`:

1. Client submits `message`, optional `provider` and optional `mode`.
2. `AgentController` validates the request DTO and delegates to `AgentExecutionService`.
3. `AgentExecutionService` resolves the selected provider through `ProviderRegistry` and creates a fresh `ReactAgent` through `ReactAgentFactory`.
4. `ReactAgent` calls the selected provider with registered tool schemas.
5. The model may propose tool calls; ARL records each proposed tool name before execution.
6. `CommerceToolRegistry` rejects unknown tools and malformed arguments, then executes registered callbacks against deterministic domain data.
7. Tool results are returned to the graph; ARL keeps observed tool results as the source of business response text.
8. The final API response contains status, message, sanitized error code when present, trace ID, provider/model metadata, observed tool names and structured results.
9. `TraceStore` retains immutable snapshots of completed traces for later lookup.

Important M1 behavior: model narrative is not exposed as business truth. A successful response is built from observed tool results, and a tool/provider failure remains a failure even if the model later claims success.

## Execution and Trust Boundaries

### Current M2 Boundary

Baseline tools are local and read-only except for `reserve_inventory`, which validates a proposal and returns `RESERVATION_NOT_ENABLED`. Protected mode creates a confirmation token for `reserve_inventory`; `POST /agent/confirm` consumes that token with a client idempotency key before stock changes.

The current trusted boundary is:

`ReactAgent -> registered ToolCallback -> CommerceToolRegistry validation -> confirmation record -> POST /agent/confirm -> SQLite transaction -> idempotent ReservationCommit`

### Remaining Protected Write Hardening

Future write execution should keep this stricter boundary:

`ReactAgent -> registered ToolCallback -> SafeToolExecutor -> strict argument validation -> business constraints -> policy -> trusted authorization -> transaction -> domain result`

Key rules for M2:

- The callback is the only protected tool entry point.
- Tool interceptors may trace, but interceptors alone are not the authorization boundary.
- The model never assigns trusted idempotency keys or grants authorization.
- Confirmation records bind operation, canonical arguments, execution context and expiry.
- SQLite atomically consumes confirmation, changes stock and records the idempotent result for the implemented reservation path; file-backed databases preserve those records across restarts.
- A unique key plus operation fingerprint prevents duplicate effects and key reuse with changed arguments.
- Ambiguous write failures are reconciled against durable records, never blindly retried.

## Provider Architecture

ARL uses Spring AI OpenAI-compatible `ChatModel` implementations for both providers:

- DeepSeek: `https://api.deepseek.com/chat/completions`
- Grok: `https://api.x.ai/v1/chat/completions`

Design decisions:

- Only `deepseek` and `grok` are valid provider IDs.
- There is no automatic provider fallback.
- Missing credentials keep providers unconfigured; clients are not constructed for absent keys.
- Provider defaults are immutable after registry construction.
- Each invocation gets fresh agent/hook state; checkpoint/thread IDs are not shared.
- SDK retries are disabled; model calls are bounded by the application hook.
- DeepSeek receives a transport-level `thinking: {type: disabled}` workaround until an upgraded adapter passes equivalent wire tests.
- Grok must not receive DeepSeek-only options such as `ARL_LLM_TEMPERATURE`.

## Trace and Response Model

Traces are diagnostic evidence, not durable audit records in M2. The latest 256 completed traces are retained in memory and evicted afterward.

Trace events include:

- `AGENT_STARTED`
- `MODEL_CALLED`
- `MODEL_COMPLETED`
- `MODEL_FAILED`
- `TOOL_PROPOSED`
- `TOOL_EXECUTED`
- `TOOL_FAILED`
- `AGENT_COMPLETED`

Responses include `status`, `message`, `errorCode`, `traceId`, `provider`, `model`, `toolCalls` and `results`. Provider exceptions and routing errors are sanitized; raw provider details and credentials are not returned.

## Key Design Decisions

- **Use Alibaba `ReactAgent`, not a custom ReAct loop.** ARL verifies framework integration instead of reimplementing graph execution.
- **Keep core independent from Boot composition.** `arl-core` contains reusable agent/domain logic; `arl-server` wires runtime beans and HTTP transport.
- **Prefer deterministic tool results over model narrative.** The model can select tools, but observed tool output drives business responses.
- **Fail explicitly rather than falling back.** Unknown provider, unconfigured provider, unsupported mode and invalid requests have explicit error codes.
- **Bound execution.** M2 caps model calls, accepted tool proposals, provider timeout, output tokens and trace retention.
- **Confirm writes outside the model loop.** The model may propose a reservation, but stock changes only through `POST /agent/confirm` with a client idempotency key.
- **Separate offline verification from live provider claims.** Stubbed tests prove wiring and regression behavior; live accuracy/entitlement remain opt-in evidence.
- **Keep evaluation deterministic by default.** M3 scenarios use scripted models and isolated SQLite state so reports are reproducible and do not depend on paid provider behavior.

## Current Phase vs Upcoming Changes

Current M3 implementation:

- Synthetic commerce agent with REST chat, protected reservation confirmation and traces.
- `BASELINE` mode for reads/proposals and `PROTECTED` mode for confirmation-gated reservation proposals.
- SQLite-backed catalog state with optional file persistence and in-memory traces.
- DeepSeek/Grok provider configuration and offline transport regressions.
- Confirmed reservation execution with idempotency-key replay protection and stock mutation.
- Offline evaluation runner with JSON report metrics for safety, trace completeness and protected writes.

Upcoming planned changes:

- Live/provider-quality evaluation remains future work; M3 currently covers deterministic offline behavior only.
- **M4**: frontend dashboard consuming server APIs without owning authorization decisions.

Do not claim M3/M4 behavior until the relevant milestone is approved, implemented and verified by `make verify` plus any required live/manual evidence.
