# Framework Compatibility - M0

> Historical M0 compatibility snapshot. This records the dependency/API investigation
> and test status at M0; it is not a description of current M4 functionality. See
> [project status](project-status.md), [current requirements](requirements.md), and
> [architecture](architecture.md) for the current application.

Verified on 2026-10-18, macOS arm64. Status: **M0 locally verified with limitations**.
No commerce functionality was implemented before a minimal ReactAgent compiled and
executed. Java and Spring Boot were not downgraded.

## Exact selected dependencies

| Component | Coordinate / installation | Version |
|---|---|---|
| OpenJDK | Local Oracle OpenJDK | 27+35-2325 |
| Compiler target | Maven `java.version` / release | 27 |
| Spring Boot | `org.springframework.boot:spring-boot-starter-parent` | 4.0.8 |
| Spring Framework | Boot-managed `org.springframework:spring-core` | 7.0.9 |
| Spring AI BOM | `org.springframework.ai:spring-ai-bom` | 2.0.0-M1 |
| OpenAI-compatible adapter | `org.springframework.ai:spring-ai-openai` | 2.0.0-M1 |
| Alibaba Agent | `com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework` | 2.0.0-M1.1 |
| Alibaba Graph | `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` | 2.0.0-M1.1 |
| Maven | Apache distribution | 3.9.11 |
| Maven Wrapper | Apache only-script distribution | 3.3.4 |

Direct Maven Central metadata inspection also found Spring Boot stable 4.1.1,
Spring AI stable 2.0.1 and Alibaba 1.1.2.3 / 1.1.2.4-security-fix on its 1.x line.
Alibaba's only published 2.x Agent release was 2.0.0-M1.1. Maven's `release` metadata
includes milestone releases; it is not a guarantee of GA maturity.

The selected AI version matches Alibaba's published graph POM. Boot 4.0.8 is a patch
update within Alibaba's declared Boot 4.0 line. We did **not** substitute Spring AI
2.0.1 just because it is newer: binary compatibility with Alibaba was not established.
This stack contains milestones and must not be presented as an all-stable stack.

## Compatibility result and boundaries

- Java 27 compilation, Spring class metadata reading, actual ReactAgent invocation,
  tool round trips, bounded calls and Boot context startup pass locally.
- Boot 4.0.8's official support range ends at Java 26. Spring Core 7.0.9 source contains
  `Opcodes.V27` and its class reader accepts these classes. This is local executable
  evidence, **not** official Java 27 support or production certification.
- To require an upstream-supported Java range, the smallest proposed adjustment is
  Java 26 while keeping the Boot 4 line. That change has **not** been made or tested.
  Alternatively retain Java 27 as an experimental target until upstream support lands.
- An all-GA Alibaba Boot 4 combination was not found. A 1.x Alibaba downgrade would
  change the requested framework alignment and is not applied.
- Boot 4 and the pinned AI/Alibaba libraries bring both Jackson 3 and Jackson 2 into
  the process. Do not globally replace either family's mapper or assume DTO compatibility.
- Maven emits Java 27 warnings about Unsafe and reflective final-field mutation in its
  own Guice/Sisu dependencies. Builds succeed; no suppression flags were added.

## Actual API inspection

The published `-sources.jar` files were inspected, rather than relying on main-branch examples.

| Concern | Published API | M0 verification / future use |
|---|---|---|
| Agent construction | `ReactAgent.builder().name(...).model(ChatModel).build()` | compiled and invoked |
| Invocation | `call(String)` returns `AssistantMessage` | real graph, scripted model and HTTP stub |
| Tool registration | `Builder.tools(ToolCallback...)`, `methodTools(Object...)` | callback round trip tested |
| Model options | `Builder.chatOptions(ChatOptions)` | provider defaults isolated in ChatModels |
| Hooks | `Builder.hooks(Hook...)` | fresh `ModelCallLimitHook` per agent |
| Interceptors | `Builder.interceptors(Interceptor...)` | source inspected; tracing/policy planned in M2 |
| HITL | `HumanInTheLoopHook`, interruption metadata and tool feedback | source inspected; not implemented/tested in M0 |
| Checkpointing | `Builder.saver(BaseCheckpointSaver)`, `CompileConfig` | source inspected; durable application state deferred |
| Graph limit | `CompileConfig.builder().recursionLimit(...)` | 64 configured |
| Model limit | `ModelCallLimitHook.builder().runLimit(4).exitBehavior(ERROR)` | looping model test passes |
| Structured output | `Builder.outputSchema(...)`, `outputType(...)` | API exists; application validation/repair deferred to M2 |

The default builder uses `Integer.MAX_VALUE` as the recursion limit. A trial limit of
12 graph steps ended with a partial response before the model-call hook fired; 64
allows the four-call hook to terminate explicitly. Graph steps and model calls are
not equivalent counters. M2 must additionally validate incomplete final results.

Framework HITL supports edited arguments and its source has a path that treats missing
per-tool feedback as approved. It therefore cannot be the sole authorization boundary.
M2 must bind confirmation to exact arguments and consume it atomically with the write.
Checkpoint storage alone cannot provide that guarantee.

## Provider integration and limitations

Both providers use the supported Spring AI OpenAI-compatible `OpenAiChatModel` through
the `ChatModel` interface. Each registry holds separate clients; each call gets a fresh
agent and hook. Missing keys do not instantiate a client. Unknown IDs produce
`UNKNOWN_PROVIDER`; absent credentials produce `PROVIDER_NOT_CONFIGURED`. No fallback.
These are Java errors in M0; REST error mapping belongs to M1.

| Provider | Model default | Actual endpoint | Live result |
|---|---|---|---|
| DeepSeek | `deepseek-flash` | `https://api.deepseek.com/chat/completions` | M0 snapshot: NOT_RUN; later operator-reported live verification is recorded below |
| Grok | `grok-4.7` | `https://api.x.ai/v1/chat/completions` | NOT_RUN: key absent |

### Later Live Smoke-Test Evidence

This evidence postdates the M0 snapshot and was reported by the operator, not produced by
the historical M0 or current CI run:

| Provider | Date | Model | Command | Result |
|---|---|---|---|---|
| DeepSeek | 2026-10-21 | `deepseek-flash` | `./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test` | 2 passed, 2 skipped (Grok tests skipped without credentials) |
| Grok | - | `grok-4.7` | Same opt-in command | NOT_RUN; no live result supplied |

Models are configurable. Official DeepSeek pricing documentation currently lists
`deepseek-flash` and `deepseek-v4-pro`; xAI's current chat-completions example uses
`grok-4.7`. Model availability for an individual account is not verified. xAI calls
Chat Completions a legacy endpoint; migration to Responses must be evaluated separately.

### DeepSeek workaround

The pinned native `DeepSeekChatModel.createRequest` constructs outgoing assistant
messages with null reasoning content, even though it reads reasoning content on receipt.
Current DeepSeek thinking-mode tool turns require that content to be passed back.
The pinned OpenAI adapter cannot preserve it either. M0 explicitly disables thinking;
**thinking-mode integration is not supported or verified in this iteration**.

The first HTTP test also proved that `OpenAiChatOptions.extraBody` did not survive the
pinned adapter's tool-request construction. A narrowly scoped RestClient interceptor
sets the documented top-level `thinking: {type: disabled}` field and updates content
length. This uses Spring AI's HTTP client; it does not implement a custom model client
or reasoning loop. The wire test verifies the field alongside the tool round trip.
Remove this workaround only after an upgraded adapter passes equivalent tests.

### Capabilities

Descriptors distinguish DOCUMENTED, UNVERIFIED and NOT_ENABLED. For documented default
models, tool calling, structured output and usage are documentation-level claims only.
Strict schema behavior remains UNVERIFIED. Streaming is NOT_ENABLED. Thinking/reasoning
controls are NOT_ENABLED through ARL; Grok may reason internally regardless. Unknown
model overrides receive UNVERIFIED capabilities. No claim of provider parity is made.

Provider-native structured-output behavior and malformed output recovery have not been
live-tested. Application output validation is M2. The local stub tests validate adapter
serialization and graph execution, not model quality or model schema compliance.

Connect/read timeouts default to 30 seconds. Automatic SDK retries are disabled with
`RetryPolicy.maxRetries(0)`. Tests demonstrate a one-second read timeout and one HTTP
attempt for a 503. Total graph deadlines, safe-tool retry policies and API-level error
mapping remain later work. Output is capped at 1,024 tokens for this smoke foundation.

## Reproduction and evidence

```sh
./mvnw clean verify
make run
# Opt-in paid smoke tests; absent credentials cause skips:
./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test
```

The implementation session used `MAVEN_USER_HOME=/tmp/arl-wrapper` and
`-Dmaven.repo.local=/tmp/arl-m2` to keep downloaded tooling/cache within writable
locations. These flags do not change dependency versions or test behavior.
The restricted execution sandbox disallowed loopback binding; transport tests were
run with socket access. This is a local environment restriction, not a test defect.

See [M0 verification](m0-verification.json) for final result counts. Tests cover:
Java 27 scanning, minimal real agent invocation, tool result round trip, looping model
limit, provider availability/selection/config validation, both HTTP adapters, HTTP
failure without retry, read timeout, and actual Spring Boot context startup.
Live tests require both explicit opt-in and provider credentials. No benchmark was run.

## Official sources

- [Alibaba releases](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v2.0.0-M1.1)
- [Agent Maven metadata](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-agent-framework/maven-metadata.xml)
- [Published Agent source archive](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-agent-framework/2.0.0-M1.1/spring-ai-alibaba-agent-framework-2.0.0-M1.1-sources.jar)
- [Published graph dependency POM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-graph-core/2.0.0-M1.1/spring-ai-alibaba-graph-core-2.0.0-M1.1.pom)
- [Boot 4.0 requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Spring AI Maven metadata](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- [Pinned Spring AI OpenAI source](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/2.0.0-M1/spring-ai-openai-2.0.0-M1-sources.jar)
- [Pinned Spring AI DeepSeek source](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-deepseek/2.0.0-M1/spring-ai-deepseek-2.0.0-M1-sources.jar)
- [DeepSeek models](https://api-docs.deepseek.com/quick_start/pricing)
- [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode)
- [DeepSeek tool calls](https://api-docs.deepseek.com/guides/tool_calls/)
- [xAI models](https://docs.x.ai/developers/models)
- [xAI chat compatibility](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions)
