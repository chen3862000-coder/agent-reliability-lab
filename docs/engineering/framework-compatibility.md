# Framework Compatibility Notes

This document records the dependency and provider constraints that shape ARL. It is an
engineering note, not a claim of production support. Current behavior is described in
[project status](../project-status.md) and [architecture](../architecture.md).

## Selected stack

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

These versions are pinned together. The AI/Alibaba dependencies are milestone releases, so
this stack should not be presented as an all-stable combination. Do not upgrade one
component casually; re-run the wire and compatibility tests when changing the stack.

## Current compatibility caveats

- Java 27 compilation, Spring class metadata reading, ReactAgent invocation, tool round
  trips, bounded calls and Spring Boot startup pass locally.
- Spring Boot 4.0.8's documented support range ends at Java 26. Java 27 is verified by local
  executable evidence, not by an official Java 27 support statement.
- Boot 4 and the pinned AI/Alibaba libraries bring both Jackson 2 and Jackson 3 into the
  process. Do not globally replace either mapper family or assume DTO compatibility.
- Maven may emit warnings about Unsafe and reflective final-field mutation in its own
  Guice/Sisu dependencies. Builds succeed; no suppression flags are added.
- The default Alibaba graph recursion limit is high. ARL configures a bounded recursion
  limit and a separate model-call limit hook.

## Provider integration

Both providers use the Spring AI OpenAI-compatible `OpenAiChatModel` through the `ChatModel`
interface. Each provider has a separate client; each request gets a fresh agent and hook.

| Provider | Default model | Endpoint |
|---|---|---|
| DeepSeek | `deepseek-flash` | `https://api.deepseek.com/chat/completions` |
| Grok | `grok-4.7` | `https://api.x.ai/v1/chat/completions` |

Design constraints:

- Only `deepseek` and `grok` are valid provider IDs.
- Missing credentials keep a provider unconfigured; a client is not constructed.
- Unknown providers and unconfigured providers return distinct errors.
- There is no automatic provider fallback.
- Connect/read timeouts default to 30 seconds; SDK retries are disabled.
- Output is capped at 1,024 tokens and model calls are bounded by the application hook.
- Grok must not receive DeepSeek-only options such as `ARL_LLM_TEMPERATURE`.

## DeepSeek thinking workaround

The pinned adapter does not preserve DeepSeek thinking-mode tool turns correctly. ARL
therefore disables thinking through a narrowly scoped RestClient interceptor that sets the
documented top-level `thinking: {type: disabled}` field and updates content length. This uses
Spring AI's HTTP client; it does not implement a custom model client or reasoning loop.

Remove this workaround only after an upgraded adapter passes equivalent wire tests. Thinking
mode is not supported or verified in the current demo.

## Capability claims

Provider descriptors distinguish `DOCUMENTED`, `UNVERIFIED` and `NOT_ENABLED` capabilities.
For documented default models, tool calling, structured output and usage are
documentation-level claims. Strict schema behavior remains unverified. Streaming is not
enabled. Thinking/reasoning controls are not enabled through ARL; Grok may reason internally
regardless.

ARL does not configure a model `outputSchema` and does not treat free-form model narrative as
business truth. Business response text is derived from observed tool results.

## Verification

```sh
./mvnw clean verify
./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test
```

The first command runs offline verification. The second is an opt-in paid smoke test:
absent credentials cause skips, and skips are not successful live verification. No
live-provider result is checked into this repository. Live tests may incur provider charges.

## Official sources

- [Alibaba releases](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v2.0.0-M1.1)
- [Agent Maven metadata](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-agent-framework/maven-metadata.xml)
- [Published Agent source archive](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-agent-framework/2.0.0-M1.1/spring-ai-alibaba-agent-framework-2.0.0-M1.1-sources.jar)
- [Published graph dependency POM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-graph-core/2.0.0-M1.1/spring-ai-alibaba-graph-core-2.0.0-M1.1.pom)
- [Boot 4.0 requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Spring AI Maven metadata](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- [Pinned Spring AI OpenAI source](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/2.0.0-M1/spring-ai-openai-2.0.0-M1-sources.jar)
- [DeepSeek models](https://api-docs.deepseek.com/quick_start/pricing)
- [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode)
- [DeepSeek tool calls](https://api-docs.deepseek.com/guides/tool_calls/)
- [xAI models](https://docs.x.ai/developers/models)
- [xAI chat compatibility](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions)
