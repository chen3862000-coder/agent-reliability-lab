# Agent Instructions

## Current Scope
- M4 is approved and implemented: protected reservations, deterministic offline evaluation, React demo and local Docker Compose stack.
- Do not claim live evaluation or identity-bound authorization; broader commerce writes require approval.
- `docs/m1-implementation.md` is a historical snapshot; use `docs/project-status.md` for current behavior.

## Toolchain
- Use OpenJDK 27; the root `pom.xml` sets `java.version` to `27`.
- Use `./mvnw`, not a global Maven. The wrapper downloads Maven 3.9.11.
- First dependency resolution needs internet access; cached offline verification can use `./mvnw -o clean verify`.
- Normal tests bind loopback sockets for HTTP adapter stubs.

## Commands
- Canonical verification: `make verify` (also `make test`): Maven checks plus requirement evidence.
- Offline evaluation report: `make evaluate`; writes `target/evaluation/report.json` and does not call paid providers.
- Container demo: `make demo`; stop with `make demo-down`.
- Frontend verification: `make frontend-verify`; dev server: `make frontend-dev` after `make start`.
- Java-only verification: `./mvnw clean verify`; formatting: `make format`.
- Run the app: `make start` or `./scripts/start-arl-server.sh`; it builds and starts the HTTP server on loopback port 8080. `make run` is an alias.
- Focus one module: `./mvnw -pl arl-core test` or `./mvnw -pl arl-server test`.
- Focus one test class: `./mvnw -pl arl-core -Dtest=ProviderTransportTest test`.
- Paid live smoke tests are opt-in only: export vars from `.env.example`, then run `make live-test`.

## Architecture Boundaries
- `arl-core` owns provider registry/configuration and `ReactAgentFactory`; it should stay independent of Spring Boot composition.
- `arl-server` is the Spring Boot composition root and depends on `arl-core`.
- `arl-evaluation` is the offline evaluation module; `frontend` is API-client-only and must not own safety decisions.
- `SafeToolExecutor` and `ReservationPolicy` own the trusted reservation/confirmation boundary; no write retries.
- Create new modules only when they contain executable functionality.
- Use Alibaba `ReactAgent`; do not implement a custom ReAct loop.

## Code Documentation
- Document contracts, not syntax. Prioritize public APIs and key classes where a caller could misuse the code from the signature alone: mutation versus proposal, idempotency and retry rules, expiry/lifecycle, resource ownership, execution limits, error semantics, and evidence scope.
- Add class-level Javadoc to public classes/interfaces whose purpose or trust boundary is not obvious. Add method-level Javadoc for non-obvious public methods; use `@param`, `@return`, and `@throws` when behavior is not evident from names and types.
- Do not add blanket Javadoc. Simple records, DTO accessors, trivial overrides, and self-explanatory private helpers should stay uncommented; comments must explain intent or guarantees, not restate code.
- Keep comments accurate and current. Update affected Javadocs when behavior changes, and do not claim live evaluation, identity-bound authorization, durable audit, or other capabilities the code does not implement.
- Prefer `{@link}`/`{@code}` for code references. Keep comments concise and account for Checkstyle file-length limits; refactor or condense rather than weakening or suppressing checks.
- When adding or changing a public contract, include its documentation in the same change and run `make format` plus `make verify`.

## Provider Gotchas
- The app does not auto-load `.env`; environment variables must already be exported.
- `ARL_DB_PATH` controls SQLite persistence; blank means in-memory stock/idempotency reset on restart.
- Only `deepseek` and `grok` are valid provider IDs; there is no automatic provider fallback.
- Missing credentials must keep providers unconfigured rather than constructing clients.
- Each invocation should get a fresh `ReactAgent`/hook; do not share checkpoint or thread IDs.
- Keep the DeepSeek `thinking: {type: disabled}` transport workaround unless an upgraded adapter passes equivalent HTTP tests.
- Grok must not receive `ARL_LLM_TEMPERATURE`; the current code applies temperature only to DeepSeek.

## Testing Notes
- Default tests use scripted models and loopback HTTP stubs, not paid provider APIs.
- Live provider tests require both `-Darl.live=true` and the relevant API key; skipped live tests are `NOT_RUN`, not successful verification.
- `ProviderTransportTest` is the executable check for provider paths, DeepSeek thinking disablement, tool round trip, timeout, and no retry.

## Compatibility Constraints
- Spring Boot 4.0.8, Spring AI 2.0.0-M1, and Alibaba Agent Framework 2.0.0-M1.1 are pinned together; do not upgrade one casually.
- Java 27 works by local executable evidence, but Boot's documented support stops at Java 26; preserve this caveat in claims.
- Boot 4 and the pinned AI/Alibaba stack bring Jackson 3 and Jackson 2 into the process; do not globally replace either mapper family.

## Engineering Quality Gate
- Read `docs/quality-harness.md` and `docs/code-review.md` before changing behavior.
- Update `config/requirements.json` mappings when adding/renaming acceptance tests.
- Run `make verify` on the final source state. Use its generated evidence, not the historical M0 JSON.
- Do not lower coverage floors, suppress checks, or reclassify automated requirements merely to pass.
- Do not weaken Checkstyle or PMD CPD smell checks to pass; refactor large, complex or duplicated code instead.
- Keep manual review, live provider verification and deferred milestones separate from automated passes.
- Keep engineering verification, offline evaluation, manual review and live-provider evidence distinct.

- Coverage gates per Java module: CLASS and METHOD >=71% (strictly above 70%); LINE >=85%; BRANCH >=75%.
