package dev.arl.server;

import dev.arl.agent.AgentExecutionService;
import dev.arl.agent.AgentResponse;
import dev.arl.llm.LlmProviderRegistry;
import dev.arl.observability.TraceStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AgentController {
  public record ChatRequest(
      @NotBlank @Size(max = 4000) String message,
      @Size(max = 32) String provider,
      @Size(max = 32) String mode) {}

  private final AgentExecutionService agent;
  private final LlmProviderRegistry providers;
  private final TraceStore traces;

  public AgentController(
      AgentExecutionService agent, LlmProviderRegistry providers, TraceStore traces) {
    this.agent = agent;
    this.providers = providers;
    this.traces = traces;
  }

  @PostMapping("/agent/chat")
  public ResponseEntity<AgentResponse> chat(@Valid @RequestBody ChatRequest request) {
    AgentResponse result = agent.chat(request.message(), request.provider(), request.mode());
    return ResponseEntity.status(status(result.errorCode())).body(result);
  }

  static int status(String code) {
    if (code == null) return 200;
    return switch (code) {
      case "UNKNOWN_PROVIDER", "INVALID_REQUEST", "MODE_NOT_AVAILABLE" -> 400;
      case "PROVIDER_NOT_CONFIGURED" -> 503;
      case "PROVIDER_UNAVAILABLE", "INVALID_MODEL_RESPONSE" -> 502;
      default -> 422;
    };
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "milestone", "M1");
  }

  @GetMapping("/providers")
  public Object providers() {
    return providers.availableProviders();
  }

  @GetMapping("/traces/{traceId}")
  public TraceStore.Trace trace(@PathVariable String traceId) {
    return traces.find(traceId).orElseThrow(() -> new TraceNotFoundException());
  }

  static final class TraceNotFoundException extends RuntimeException {}
}
