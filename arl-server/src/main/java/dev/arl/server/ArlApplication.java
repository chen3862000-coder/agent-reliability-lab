package dev.arl.server;

import dev.arl.llm.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class ArlApplication {
  public static void main(String[] args) {
    SpringApplication.run(ArlApplication.class, args);
  }

  @Bean
  org.springframework.boot.ApplicationRunner reportProviders(ProviderRegistry registry) {
    return args ->
        registry
            .availableProviders()
            .forEach(
                info ->
                    org.slf4j.LoggerFactory.getLogger(ArlApplication.class)
                        .info(
                            "Provider={} model={} status={}",
                            info.id(),
                            info.model(),
                            info.status()));
  }

  @Bean
  ProviderRegistry providers(Environment environment) {
    return new ProviderRegistry(providerSettings(environment));
  }

  @Bean
  dev.arl.domain.CommerceCatalog catalog() {
    return new dev.arl.domain.CommerceCatalog();
  }

  @Bean
  dev.arl.tools.CommerceToolRegistry tools(dev.arl.domain.CommerceCatalog catalog) {
    return new dev.arl.tools.CommerceToolRegistry(catalog);
  }

  @Bean
  dev.arl.observability.TraceStore traces() {
    return new dev.arl.observability.TraceStore(256);
  }

  @Bean
  dev.arl.agent.AgentExecutionService agent(
      dev.arl.llm.LlmProviderRegistry providers,
      dev.arl.tools.CommerceToolRegistry tools,
      dev.arl.observability.TraceStore traces,
      Environment environment) {
    String configured = environment.getProperty("arl.llm.default-provider", "deepseek");
    return new dev.arl.agent.AgentExecutionService(
        providers, configured.isBlank() ? "deepseek" : configured, tools, traces);
  }

  private static Map<String, String> providerSettings(Environment environment) {
    Map<String, String> settings = new LinkedHashMap<>();
    put(settings, "ARL_DEFAULT_PROVIDER", environment, "arl.llm.default-provider");
    put(settings, "ARL_LLM_TIMEOUT_SECONDS", environment, "arl.llm.timeout-seconds");
    put(settings, "ARL_LLM_TEMPERATURE", environment, "arl.llm.temperature");
    put(settings, "DEEPSEEK_API_KEY", environment, "arl.llm.deepseek.api-key");
    put(settings, "DEEPSEEK_MODEL", environment, "arl.llm.deepseek.model");
    put(settings, "XAI_API_KEY", environment, "arl.llm.grok.api-key");
    put(settings, "GROK_MODEL", environment, "arl.llm.grok.model");
    return settings;
  }

  private static void put(
      Map<String, String> settings, String key, Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value != null) settings.put(key, value);
  }
}
