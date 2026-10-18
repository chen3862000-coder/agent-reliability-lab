# Architecture (M1)

“LLMs propose actions. Deterministic systems validate and execute them.”

ARL uses Alibaba ReactAgent for model interaction, tool selection, graph execution,
and agent state. It will not implement a second reasoning loop.

## Module boundaries

- **arl-core**: provider registry, per-invocation ReactAgent factory, immutable commerce fixtures,
  tool contracts, basic validation, observed-result responses and bounded traces.
  M2 adds policy, safe writes, confirmation and durable idempotency.
- **arl-server**: Spring Boot composition root, configuration, REST DTOs/controllers;
  later SQLite JDBC adapters and transaction boundaries. It depends on core.
- **arl-evaluation** (M3): independent Java runner depending on core, isolated fixtures,
  fault injection, metrics and reports. No evaluation runs during normal builds.
- **frontend** (M4): React/TypeScript client of server APIs. No authorization decisions.

Create modules only when they contain executable functionality. M1 has read-only commerce
operations; no protected mode, benchmark results or dashboard.

## Request isolation

Select a provider from an immutable registry. A fresh ReactAgent and fresh execution
configuration are created for each invocation. Reuse provider ChatModels with immutable
defaults; never mutate their options for a request. Do not share checkpoint/thread IDs.

## Planned execution boundary (M2)

ReactAgent → registered callback → SafeToolExecutor → strict argument validation →
business constraints → policy → trusted authorization → transaction → domain result.

The callback is the only protected tool entry point. Tool interceptors add tracing;
interceptors alone are not the authorization boundary. The model never assigns
trusted idempotency keys. Confirmation records bind operation, canonical arguments,
execution context and expiry. SQLite atomically consumes confirmation, changes stock
and records the idempotent result. A unique key plus operation fingerprint prevents
both duplicate effects and key reuse with changed arguments. Ambiguous write failures
are reconciled against the durable record, never blindly retried.

## Risks to resolve before commerce writes

Framework milestones and Java support gaps require explicit compatibility evidence.
Checkpointing does not provide business transaction atomicity. Framework HITL permits
edited arguments and some missing feedback; application authorization must fail closed.
SQLite serializes writers: concurrent duplicate tests and bounded lock handling matter.
Provider-native JSON is only formatting assistance, never evidence of business truth.
Baseline uses separate synthetic state and stays unavailable to public traffic.
