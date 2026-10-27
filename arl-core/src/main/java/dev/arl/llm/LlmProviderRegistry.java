package dev.arl.llm;

import java.util.List;
import org.springframework.ai.chat.model.ChatModel;

/** Resolves explicitly selected model providers without automatic fallback. */
public interface LlmProviderRegistry {
  /**
   * Resolves a provider ID; implementations may use their configured default when the ID is null.
   *
   * @throws ProviderException with {@code UNKNOWN_PROVIDER} or {@code PROVIDER_NOT_CONFIGURED}
   */
  ChatModel resolve(String providerId);

  /** Returns provider configuration and declared capabilities, not a live availability check. */
  List<LlmProviderInfo> availableProviders();
}
