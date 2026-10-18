package dev.arl.llm;

import static dev.arl.llm.LlmProviderInfo.Support.*;
import static dev.arl.llm.ProviderException.Code.*;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Immutable registry; clients are constructed only when credentials are present. */
public final class ProviderRegistry implements LlmProviderRegistry {
  private final Map<String, ChatModel> models;
  private final List<LlmProviderInfo> infos;
  private final String defaultProvider;

  public ProviderRegistry(Map<String, String> env) {
    this(env, Map.of("deepseek", "https://api.deepseek.com", "grok", "https://api.x.ai"));
  }

  ProviderRegistry(Map<String, String> env, Map<String, String> endpoints) {
    defaultProvider = value(env, "ARL_DEFAULT_PROVIDER", "deepseek");
    if (!Set.of("deepseek", "grok").contains(defaultProvider))
      throw new ProviderException(UNKNOWN_PROVIDER);
    int seconds = Integer.parseInt(value(env, "ARL_LLM_TIMEOUT_SECONDS", "30"));
    if (seconds < 1 || seconds > 300)
      throw new IllegalArgumentException("Timeout must be 1..300 seconds");
    Double temperature =
        env.containsKey("ARL_LLM_TEMPERATURE") && !env.get("ARL_LLM_TEMPERATURE").isBlank()
            ? Double.valueOf(env.get("ARL_LLM_TEMPERATURE"))
            : null;
    if (temperature != null
        && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
      throw new IllegalArgumentException("Temperature must be 0..2");
    Map<String, ChatModel> clients = new LinkedHashMap<>();
    List<LlmProviderInfo> details = new ArrayList<>();
    for (String id : List.of("deepseek", "grok")) {
      boolean deepseek = id.equals("deepseek");
      String model =
          value(
              env,
              deepseek ? "DEEPSEEK_MODEL" : "GROK_MODEL",
              deepseek ? "deepseek-flash" : "grok-4.7");
      String key = env.get(deepseek ? "DEEPSEEK_API_KEY" : "XAI_API_KEY");
      boolean configured = key != null && !key.isBlank();
      // Unknown model overrides must not inherit unverified capabilities.
      boolean documented =
          deepseek
              ? Set.of("deepseek-flash", "deepseek-v4-pro").contains(model)
              : model.equals("grok-4.7");
      var support = documented ? DOCUMENTED : UNVERIFIED;
      details.add(
          new LlmProviderInfo(
              id,
              model,
              configured
                  ? LlmProviderInfo.Status.CONFIGURED
                  : LlmProviderInfo.Status.NOT_CONFIGURED,
              new LlmProviderInfo.Capabilities(
                  support, support, UNVERIFIED, NOT_ENABLED, NOT_ENABLED, support)));
      if (!configured) continue;
      var requestFactory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(seconds)).build());
      requestFactory.setReadTimeout(Duration.ofSeconds(seconds));
      var rest = RestClient.builder().requestFactory(requestFactory);
      // Spring AI 2.0.0-M1's OpenAI request merge drops extraBody during tool registration. Keep
      // this
      // provider-specific transport workaround until an upstream upgrade is verified.
      if (deepseek)
        rest.requestInterceptor(
            (request, body, execution) -> {
              var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
              var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
              json.putObject("thinking").put("type", "disabled");
              byte[] patched = mapper.writeValueAsBytes(json);
              request.getHeaders().setContentLength(patched.length);
              return execution.execute(request, patched);
            });
      var api =
          OpenAiApi.builder()
              .baseUrl(endpoints.get(id))
              .completionsPath(deepseek ? "/chat/completions" : "/v1/chat/completions")
              .apiKey(key)
              .restClientBuilder(rest)
              .build();
      var options =
          OpenAiChatOptions.builder()
              .model(model)
              .maxTokens(1024)
              .internalToolExecutionEnabled(false);
      // This pinned adapter cannot round-trip DeepSeek reasoning_content. Explicitly disable
      // thinking.
      if (deepseek) options.temperature(temperature);
      var noRetry =
          new org.springframework.core.retry.RetryTemplate(
              org.springframework.core.retry.RetryPolicy.builder().maxRetries(0).build());
      clients.put(
          id,
          OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(options.build())
              .retryTemplate(noRetry)
              .build());
    }
    models = Map.copyOf(clients);
    infos = List.copyOf(details);
  }

  @Override
  public ChatModel resolve(String providerId) {
    String id = providerId == null ? defaultProvider : providerId;
    if (infos.stream().noneMatch(p -> p.id().equals(id)))
      throw new ProviderException(UNKNOWN_PROVIDER);
    ChatModel model = models.get(id);
    if (model == null) throw new ProviderException(PROVIDER_NOT_CONFIGURED);
    return model;
  }

  @Override
  public List<LlmProviderInfo> availableProviders() {
    return infos;
  }

  private static String value(Map<String, String> env, String key, String fallback) {
    String v = env.get(key);
    return v == null || v.isBlank() ? fallback : v;
  }
}
