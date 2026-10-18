package dev.arl.llm;

/** Contains no credentials. Configured does not mean live-verified. */
public record LlmProviderInfo(String id, String model, Status status, Capabilities capabilities) {
  public enum Status {
    CONFIGURED,
    NOT_CONFIGURED
  }

  public enum Support {
    DOCUMENTED,
    UNVERIFIED,
    NOT_ENABLED
  }

  public record Capabilities(
      Support toolCalling,
      Support structuredOutput,
      Support strictJsonSchema,
      Support streaming,
      Support reasoningMode,
      Support tokenUsage) {}
}
