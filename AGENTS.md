# Agent Instructions

## Current Scope
- M3 is approved and implemented: M2 protected reservation plus deterministic offline evaluation in `arl-evaluation`.
- Do not add Docker, frontend, live evaluation claims, or broader commerce writes without approval for later milestones.
- See `docs/m1-implementation.md` for current behavior and limitations.

## Toolchain
- Use OpenJDK 27; the root `pom.xml` sets `java.version` to `27`.
- Use `./mvnw`, not a global Maven. The wrapper downloads Maven 3.9.11.
- First dependency resolution needs internet access; cached offline verification can use `./mvnw -o clean verify`.
- Normal tests bind loopback sockets for HTTP adapter stubs.

## Commands
- Canonical verification: `make verify` (also `make test`): Maven checks plus requirement evidence.
- Offline evaluation report: `make evaluate`; writes `target/evaluation/report.json` and does not call paid providers.
- Java-only verification: `./mvnw clean verify`; formatting: `make format`.
- Run the M1 app: `make start` or `./scripts/start-arl-server.sh`; it builds and starts the HTTP server on loopback port 8080. `make run` is an alias.
- Focus one module: `./mvnw -pl arl-core test` or `./mvnw -pl arl-server test`.
- Focus one test class: `./mvnw -pl arl-core -Dtest=ProviderTransportTest test`.
- Paid live smoke tests are opt-in only: export vars from `.env.example`, then run `make live-test`.

## Architecture Boundaries
- `arl-core` owns provider registry/configuration and `ReactAgentFactory`; it should stay independent of Spring Boot composition.
- `arl-server` is the Spring Boot composition root and depends on `arl-core`.
- Create new modules only when they contain executable functionality; docs explicitly defer `arl-evaluation` and `frontend`.
- Use Alibaba `ReactAgent`; do not implement a custom ReAct loop.

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
- The engineering harness is not the M2 runtime reliability harness; do not imply M2 is implemented.

- Coverage gates per Java module: CLASS and METHOD >=71% (strictly above 70%); LINE >=85%; BRANCH >=75%.
