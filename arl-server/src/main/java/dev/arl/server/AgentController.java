package dev.arl.server;

import dev.arl.agent.AgentExecutionService;
import dev.arl.agent.AgentResponse;
import dev.arl.llm.LlmProviderRegistry;
import dev.arl.observability.TraceStore;
import dev.arl.tools.SafeToolExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP adapter for chat, confirmation, provider metadata, and diagnostic trace lookup. */
@RestController
public final class AgentController {
  public record ChatRequest(
      @NotBlank @Size(max = 4000) String message,
      @Size(max = 32) String provider,
      @Size(max = 32) String mode) {}

  public record ConfirmRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,128}") String confirmationId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,128}") String idempotencyKey) {}

  private final AgentExecutionService agent;
  private final LlmProviderRegistry providers;
  private final SafeToolExecutor safeToolExecutor;
  private final TraceStore traces;

  public AgentController(
      AgentExecutionService agent,
      LlmProviderRegistry providers,
      SafeToolExecutor safeToolExecutor,
      TraceStore traces) {
    this.agent = agent;
    this.providers = providers;
    this.safeToolExecutor = safeToolExecutor;
    this.traces = traces;
  }

  /** Runs one bounded agent chat; omitted mode/provider values use baseline/default behavior. */
  @PostMapping("/agent/chat")
  public ResponseEntity<AgentResponse> chat(@Valid @RequestBody ChatRequest request) {
    AgentResponse result = agent.chat(request.message(), request.provider(), request.mode());
    return ResponseEntity.status(status(result.errorCode())).body(result);
  }

  /**
   * Confirms a prior protected reservation proposal using a caller idempotency key.
   *
   * <p>Retry {@code WRITE_BUSY_RETRY_SAME_KEY} with the same confirmation ID and key. This local
   * demo does not authenticate callers or bind confirmations to user identity.
   */
  @PostMapping("/agent/confirm")
  public ResponseEntity<dev.arl.domain.CommerceCatalog.ReservationCommit> confirm(
      @Valid @RequestBody ConfirmRequest request) {
    var result =
        safeToolExecutor.confirmReservation(request.confirmationId(), request.idempotencyKey());
    return ResponseEntity.status(status(result.errorCode())).body(result);
  }

  static int status(String code) {
    if (code == null) return 200;
    return switch (code) {
      case "UNKNOWN_PROVIDER", "INVALID_REQUEST", "MODE_NOT_AVAILABLE" -> 400;
      case "PROVIDER_NOT_CONFIGURED", "WRITE_BUSY_RETRY_SAME_KEY" -> 503;
      case "PROVIDER_UNAVAILABLE", "INVALID_MODEL_RESPONSE" -> 502;
      default -> 422;
    };
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/providers")
  public Object providers() {
    return providers.availableProviders();
  }

  /** Returns a retained in-memory trace; traces are diagnostic, not durable audit records. */
  @GetMapping("/traces/{traceId}")
  public TraceStore.Trace trace(@PathVariable String traceId) {
    return traces.find(traceId).orElseThrow(() -> new TraceNotFoundException());
  }

  static final class TraceNotFoundException extends RuntimeException {}
}
