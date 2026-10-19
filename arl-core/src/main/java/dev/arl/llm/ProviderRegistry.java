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
    var settings = settings(env);
    Map<String, ChatModel> clients = new LinkedHashMap<>();
    List<LlmProviderInfo> details = new ArrayList<>();
    for (String id : List.of("deepseek", "grok")) {
      var descriptor = descriptor(env, id);
      details.add(info(descriptor));
      if (descriptor.configured()) clients.put(id, model(descriptor, endpoints.get(id), settings));
    }
    models = Map.copyOf(clients);
    infos = List.copyOf(details);
  }

  private static RegistrySettings settings(Map<String, String> env) {
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
    return new RegistrySettings(seconds, temperature);
  }

  private static ProviderDescriptor descriptor(Map<String, String> env, String id) {
    boolean deepseek = id.equals("deepseek");
    String model =
        value(
            env,
            deepseek ? "DEEPSEEK_MODEL" : "GROK_MODEL",
            deepseek ? "deepseek-flash" : "grok-4.7");
    String key = env.get(deepseek ? "DEEPSEEK_API_KEY" : "XAI_API_KEY");
    return new ProviderDescriptor(id, deepseek, model, key, key != null && !key.isBlank());
  }

  private static LlmProviderInfo info(ProviderDescriptor descriptor) {
    // Unknown model overrides must not inherit unverified capabilities.
    boolean documented =
        descriptor.deepseek()
            ? Set.of("deepseek-flash", "deepseek-v4-pro").contains(descriptor.model())
            : descriptor.model().equals("grok-4.7");
    var support = documented ? DOCUMENTED : UNVERIFIED;
    return new LlmProviderInfo(
        descriptor.id(),
        descriptor.model(),
        descriptor.configured()
            ? LlmProviderInfo.Status.CONFIGURED
            : LlmProviderInfo.Status.NOT_CONFIGURED,
        new LlmProviderInfo.Capabilities(
            support, support, UNVERIFIED, NOT_ENABLED, NOT_ENABLED, support));
  }

  private static ChatModel model(
      ProviderDescriptor descriptor, String endpoint, RegistrySettings settings) {
    var api = api(descriptor, endpoint, settings.seconds());
    var options =
        OpenAiChatOptions.builder()
            .model(descriptor.model())
            .maxTokens(1024)
            .internalToolExecutionEnabled(false);
    if (descriptor.deepseek()) options.temperature(settings.temperature());
    var noRetry =
        new org.springframework.core.retry.RetryTemplate(
            org.springframework.core.retry.RetryPolicy.builder().maxRetries(0).build());
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(options.build())
        .retryTemplate(noRetry)
        .build();
  }

  private static OpenAiApi api(ProviderDescriptor descriptor, String endpoint, int seconds) {
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(seconds)).build());
    requestFactory.setReadTimeout(Duration.ofSeconds(seconds));
    var rest = RestClient.builder().requestFactory(requestFactory);
    if (descriptor.deepseek()) rest.requestInterceptor(ProviderRegistry::disableDeepseekThinking);
    return OpenAiApi.builder()
        .baseUrl(endpoint)
        .completionsPath(descriptor.deepseek() ? "/chat/completions" : "/v1/chat/completions")
        .apiKey(descriptor.key())
        .restClientBuilder(rest)
        .build();
  }

  private static org.springframework.http.client.ClientHttpResponse disableDeepseekThinking(
      org.springframework.http.HttpRequest request,
      byte[] body,
      org.springframework.http.client.ClientHttpRequestExecution execution)
      throws java.io.IOException {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
    json.putObject("thinking").put("type", "disabled");
    byte[] patched = mapper.writeValueAsBytes(json);
    request.getHeaders().setContentLength(patched.length);
    return execution.execute(request, patched);
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

  private record RegistrySettings(int seconds, Double temperature) {}

  private record ProviderDescriptor(
      String id, boolean deepseek, String model, String key, boolean configured) {}
}
