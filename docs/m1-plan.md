# M1 implementation plan — approved and implemented

> Historical milestone plan. It records M1's original scope and acceptance criteria;
> subsequent M2-M4 work has since been approved and implemented. See
> [project status](project-status.md) for current behavior.

See [implementation and verification](m1-implementation.md) for delivered behavior.

1. Revisit M0 compatibility findings and approve any required stack adjustment first.
2. Add immutable product, inventory and order records plus deterministic synthetic
   fixtures (including SKU-1001 with 20 units). Keep fixture reset explicit.
3. Define four tool contracts: get_product, get_inventory, get_order and a reservation
   proposal. Store names, input/output schemas, side-effect classification, authorization,
   timeout and retry metadata centrally. Do not enable an executable write before M2.
4. Register read-only ToolCallbacks with the existing ReactAgent factory. Add basic
   argument parsing and structured errors; reject unknown tools and malformed input.
5. Implement POST /agent/chat with validated request DTOs, per-request provider
   selection, a fresh execution context, provider/model identifiers and a trace ID.
   Unknown providers and absent credentials return explicit errors without fallback.
6. Record model/tool invocation events and expose GET /traces/{traceId}. Do not log
   credentials or raw authorization material. Add GET /health and provider status.
7. Test product/inventory/order queries using scripted ChatModels through real ReactAgent;
   cover missing records, wrong tool names, bad arguments, unavailable providers,
   concurrent request isolation and bounded execution. Add opt-in live smoke coverage.
8. Verify the Maven reactor and document sample requests and actual integration status.

M1 acceptance: accurate read queries, observable tool calls and provider selection.
No side-effecting reservation is enabled until M2 supplies authorization, confirmation
and transactional idempotency. M2/M3/M4 remain separate approval-gated iterations.
