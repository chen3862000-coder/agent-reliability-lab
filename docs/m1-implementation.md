# M1 implementation and review

M1 is implemented as a local, read-only synthetic commerce agent. Alibaba ReactAgent
owns the reasoning loop. An immutable provider registry supplies the selected model;
each request gets its own agent, model-call budget, results and trace.

## API and fixtures

- `POST /agent/chat`: JSON `message` (1–4000 characters), optional `provider`
  (`deepseek` or `grok`) and optional `mode` (`BASELINE` only).
- `GET /health`: process health; does not establish provider connectivity.
- `GET /providers`: configuration and capability metadata; no keys.
- `GET /traces/{traceId}`: completed events, provider and model; unknown/evicted IDs return 404.

Fixtures: SKU-1001 Mechanical Keyboard, USD 7900 cents, 20 units; SKU-1002 Wireless
Mouse, USD 2900 cents, zero units; order 123 contains five SKU-1001 units, PROCESSING.
All fixtures are immutable and deterministic. Restart clears traces, not fixture values.

Tools `get_product`, `get_inventory` and `get_order` return structured records.
`reserve_inventory` checks SKU, positive integer quantity and available stock, then
returns `RESERVATION_NOT_ENABLED`. It cannot mutate inventory or issue confirmation.
Central contracts include schemas, side-effect/authorization metadata, local execution
policy and zero retries. JSON parsing rejects duplicate keys, trailing JSON, extra
fields and invalid types; IDs must match `[A-Za-z0-9-]{1,64}`.

Responses contain `status`, `message`, `errorCode`, `traceId`, `provider`, `model`,
`toolCalls` and `results`. Only observed tool results produce successful response text;
model narrative is not exposed as business facts. A tool failure remains a failure even
if the model subsequently claims success. No tool use returns `UNSUPPORTED_REQUEST`.
This prevents invented facts, but does not prove that a live model understood intent
or selected the right record. Live quality evaluation remains outstanding.

HTTP status: 200 success; 400 invalid request/provider/mode; 503 unconfigured provider;
502 provider failure/invalid model response; 422 tool/business/unsupported/limit errors.
Routing errors use 404/405/415. Errors contain sanitized codes and queryable trace IDs.

## Bounds and limitations

Model calls are capped at four; at most eight proposed tool calls are accepted per
request, with sequential execution. Provider connect/read timeouts remain configured
separately; these are not an end-to-end wall-clock deadline. Tools perform local memory
reads without network I/O. The latest 256 completed traces are retained, with immutable
snapshots and eviction; prompts, raw arguments and provider errors are not stored.

This is a loopback demo with synthetic data, no authentication or durable trace store.
Do not expose it as a production commerce API. M2 authorization, exact-argument
confirmation, transactional idempotency and recovery are not implemented. `PROTECTED`
is rejected explicitly. Existing Java 27/framework milestone caveats still apply.

## Review findings and regression evidence

Implementation-session self-review followed `code-review.md`; it is not independent
maintainer approval. Two concrete framework integration defects were found and fixed:

1. Model exceptions can become normal graph messages, which initially mislabeled
   provider failure and unknown tool proposals as unsupported requests. A per-request
   failure latch preserves sanitized error codes outside graph state and prevents
   subsequent model calls after a failure.
2. The model-call hook can throw its limit exception directly. The orchestration
   boundary now handles both direct and wrapped limit exceptions consistently.

`AgentExecutionServiceTest` reproduces these failures, checks fabricated model replies,
read results, tool failures and concurrent isolation. `CommerceToolRegistryTest` covers
contracts, malformed arguments, missing records, quantity constraints and unchanged
stock after repeated proposals. `AgentApiTest` uses a real HTTP server and scripted
models to verify reads, traces, request validation and error status mapping.
`TraceStoreTest` checks snapshot immutability and eviction.

Run `make verify` for fresh source-bound evidence, regression results and module-level
coverage gates. Requirements are mapped in `config/requirements.json`; generated evidence
is in `target/quality/requirements-report.json`. Both class and method coverage must be
at least 71% in each module, with line 85% and branch 75% floors unchanged.

Opt-in live compatibility and M1 inventory tests exist for both providers. They have
not been executed with credentials in this implementation session; live accuracy and
account/model entitlement remain NOT_RUN. M2, M3 and M4 remain deferred.
