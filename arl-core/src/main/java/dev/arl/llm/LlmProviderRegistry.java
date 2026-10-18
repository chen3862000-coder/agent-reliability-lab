package dev.arl.llm;

import java.util.List;
import org.springframework.ai.chat.model.ChatModel;

public interface LlmProviderRegistry {
  ChatModel resolve(String providerId);

  List<LlmProviderInfo> availableProviders();
}
