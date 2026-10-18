# Requirements and Configuration

## Toolchain

- OpenJDK 27; the Maven reactor compiles with `java.version=27`.
- Maven Wrapper 3.3.4 downloads Maven 3.9.11; use `./mvnw`, not a global Maven.
- Python 3.9+ is required for the canonical quality gate.
- First dependency resolution needs internet access. Cached Java verification can use `./mvnw -o clean verify`.
- Normal tests require loopback socket access for HTTP adapter stubs.

## Commands

```sh
make verify       # canonical local/CI gate
make format       # apply Java formatting
make start        # build and start arl-server on 127.0.0.1:8080 by default
make run          # alias for make start
make live-test    # opt-in paid provider smoke tests
```

Focused Java commands:

```sh
./mvnw clean verify
./mvnw -pl arl-core test
./mvnw -pl arl-server -am test
./mvnw -pl arl-core -Dtest=ProviderTransportTest test
```

## Application Configuration

Runtime defaults are in `arl-server/src/main/resources/application.yml`. Secrets should be
supplied through environment variables rather than committed files. ARL does not
automatically load `.env`; export variables in the shell that starts the app.

| Variable | Default / meaning |
|---|---|
| `ARL_PORT` | `8080`; HTTP port, bound to `127.0.0.1` by default |
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

## Provider Behavior

- DeepSeek uses `https://api.deepseek.com/chat/completions`.
- Grok uses `https://api.x.ai/v1/chat/completions`.
- There is no automatic fallback between providers.
- Missing credentials keep a provider unconfigured; clients are not constructed for absent keys.
- Each agent request gets fresh execution state; checkpoint/thread IDs are not shared.
- Output is capped at 1,024 tokens, model calls at four and SDK retries are disabled.

## Verification Notes

`make verify` runs Python gate tests, Maven clean verification, formatting checks,
Checkstyle, JUnit, JaCoCo coverage gates, architecture checks and fresh requirement
evidence generation.

Generated evidence is written to `target/quality/requirements-report.json`. The historical
`docs/m0-verification.json` is not evidence for current source state.

Live tests require both explicit opt-in and provider credentials:

```sh
make live-test
```

If a provider key is absent, that provider's live test is skipped and should be treated as
`NOT_RUN`, not successful live verification.
