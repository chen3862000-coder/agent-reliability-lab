package dev.arl.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {
  @Test
  void absentCredentialsDoNotConstructClientsOrFallback() {
    var registry = new ProviderRegistry(Map.of());
    assertEquals(2, registry.availableProviders().size());
    assertTrue(
        registry.availableProviders().stream()
            .allMatch(p -> p.status() == LlmProviderInfo.Status.NOT_CONFIGURED));
    assertEquals(
        ProviderException.Code.PROVIDER_NOT_CONFIGURED,
        assertThrows(ProviderException.class, () -> registry.resolve(null)).code());
    assertEquals(
        ProviderException.Code.PROVIDER_NOT_CONFIGURED,
        assertThrows(ProviderException.class, () -> registry.resolve("grok")).code());
    assertEquals(
        ProviderException.Code.UNKNOWN_PROVIDER,
        assertThrows(ProviderException.class, () -> registry.resolve("other")).code());
  }

  @Test
  void configuredClientsRemainSeparateAndDefaultIsExplicit() {
    var registry =
        new ProviderRegistry(
            Map.of(
                "DEEPSEEK_API_KEY",
                "synthetic",
                "XAI_API_KEY",
                "synthetic",
                "ARL_DEFAULT_PROVIDER",
                "grok"));
    assertSame(registry.resolve("grok"), registry.resolve(null));
    assertNotSame(registry.resolve("grok"), registry.resolve("deepseek"));
    assertFalse(registry.availableProviders().toString().contains("synthetic"));
  }

  @Test
  void configurationValidationAndUnknownModelCapabilities() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderRegistry(Map.of("ARL_LLM_TIMEOUT_SECONDS", "0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderRegistry(Map.of("ARL_LLM_TEMPERATURE", "NaN")));
    assertThrows(
        ProviderException.class,
        () -> new ProviderRegistry(Map.of("ARL_DEFAULT_PROVIDER", "other")));
    assertEquals(
        LlmProviderInfo.Support.UNVERIFIED,
        new ProviderRegistry(Map.of("DEEPSEEK_MODEL", "custom"))
            .availableProviders()
            .getFirst()
            .capabilities()
            .toolCalling());
  }

  @Test
  void defaultDoesNotFallbackToOnlyConfiguredProvider() {
    var registry = new ProviderRegistry(Map.of("XAI_API_KEY", "synthetic"));
    assertEquals(
        ProviderException.Code.PROVIDER_NOT_CONFIGURED,
        assertThrows(ProviderException.class, () -> registry.resolve(null)).code());
    assertNotNull(registry.resolve("grok"));
    assertThrows(UnsupportedOperationException.class, () -> registry.availableProviders().clear());
  }

  @Test
  void blankCredentialsAreUnavailableAndBlankConfigurationUsesDefaults() {
    var registry =
        new ProviderRegistry(
            Map.of(
                "DEEPSEEK_API_KEY",
                "  ",
                "XAI_API_KEY",
                "",
                "DEEPSEEK_MODEL",
                " ",
                "ARL_DEFAULT_PROVIDER",
                "",
                "ARL_LLM_TIMEOUT_SECONDS",
                "",
                "ARL_LLM_TEMPERATURE",
                ""));
    assertEquals("deepseek-flash", registry.availableProviders().getFirst().model());
    assertTrue(
        registry.availableProviders().stream()
            .allMatch(p -> p.status() == LlmProviderInfo.Status.NOT_CONFIGURED));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"-1", "301", "abc"})
  void rejectsInvalidTimeout(String value) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderRegistry(Map.of("ARL_LLM_TIMEOUT_SECONDS", value)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"-0.1", "2.1", "Infinity", "abc"})
  void rejectsInvalidTemperature(String value) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderRegistry(Map.of("ARL_LLM_TEMPERATURE", value)));
  }
}
