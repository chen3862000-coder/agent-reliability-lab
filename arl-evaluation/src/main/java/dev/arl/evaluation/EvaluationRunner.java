package dev.arl.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.arl.agent.AgentExecutionService;
import dev.arl.agent.AgentResponse;
import dev.arl.domain.CommerceCatalog;
import dev.arl.llm.LlmProviderInfo;
import dev.arl.llm.LlmProviderRegistry;
import dev.arl.llm.ProviderRegistry;
import dev.arl.observability.TraceStore;
import dev.arl.tools.CommerceToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

public final class EvaluationRunner {
  private static final Map<String, String> METRIC_DEFINITIONS =
      Map.of(
          "toolAccuracy",
              "Among scenarios expecting a registered tool, returned executed-tool names exactly match expected names.",
          "safetyViolations",
              "Scenarios violating expected stock invariance or confirmed stock result.",
          "idempotency", "Protected writes replay stored results and reject idempotency-key reuse.",
          "recovery", "Protected writes recover the committed result on a later replay.",
          "traceCompleteness",
              "Traces contain lifecycle plus applicable model/tool outcome events.");
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public EvaluationReport run() {
    List<EvaluationReport.ScenarioResult> results = scenarios().stream().map(this::run).toList();
    return new EvaluationReport(
        "offline-m4", Instant.now(), METRIC_DEFINITIONS, metrics(results), results);
  }

  public EvaluationReport write(Path destination) throws IOException {
    EvaluationReport report = run();
    Files.createDirectories(destination.toAbsolutePath().getParent());
    JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
    return report;
  }

  private EvaluationReport.ScenarioResult run(EvaluationScenario scenario) {
    try (var catalog = new CommerceCatalog()) {
      var traces = new TraceStore(32);
      var response =
          agent(scenario, catalog, traces).chat(scenario.message(), null, scenario.mode());
      return assess(scenario, catalog, traces, response);
    }
  }

  private static AgentExecutionService agent(
      EvaluationScenario scenario, CommerceCatalog catalog, TraceStore traces) {
    LlmProviderRegistry registry =
        new LlmProviderRegistry() {
          public ChatModel resolve(String id) {
            return prompt -> scriptedResponse(scenario, prompt);
          }

          public List<LlmProviderInfo> availableProviders() {
            return new ProviderRegistry(Map.of()).availableProviders();
          }
        };
    return new AgentExecutionService(
        registry, "deepseek", new CommerceToolRegistry(catalog), traces);
  }

  private static ChatResponse scriptedResponse(
      EvaluationScenario scenario, org.springframework.ai.chat.prompt.Prompt prompt) {
    if (scenario.failureKind() != null) return failureResponse(scenario);
    boolean hasToolResponse =
        prompt.getInstructions().stream()
            .anyMatch(message -> message instanceof ToolResponseMessage);
    if (hasToolResponse || scenario.tool() == null) {
      return new ChatResponse(
          List.of(new Generation(new AssistantMessage("model narrative ignored"))));
    }
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "eval-tool", "function", scenario.tool(), scenario.arguments())))
                    .build())));
  }

  static ChatResponse failureResponse(EvaluationScenario scenario) {
    return switch (scenario.failureKind()) {
      case "provider-exception" -> throw new IllegalStateException("simulated provider outage");
      case "null-response" -> null;
      case "empty-response" -> new ChatResponse(List.of());
      case "tool-call-budget" -> toolBudgetResponse();
      case "completion-exception" ->
          throw new java.util.concurrent.CompletionException(
              new IllegalStateException("simulated completion failure"));
      default -> throw new IllegalArgumentException("Unknown fault: " + scenario.failureKind());
    };
  }

  private static ChatResponse toolBudgetResponse() {
    var calls =
        java.util.stream.IntStream.range(0, 9)
            .mapToObj(
                index ->
                    new AssistantMessage.ToolCall(
                        "eval-tool-" + index,
                        "function",
                        "get_inventory",
                        "{\"sku\":\"SKU-1001\"}"))
            .toList();
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
  }

  EvaluationReport.ScenarioResult assess(
      EvaluationScenario scenario,
      CommerceCatalog catalog,
      TraceStore traces,
      AgentResponse response) {
    List<String> failures = new ArrayList<>();
    var events = traceEvents(traces, response);
    boolean toolAccurate = response.toolCalls().equals(scenario.expectedToolCalls());
    boolean safetyPassed = catalog.inventory(scenario.sku()).available() == scenario.stockBefore();
    expect("status", scenario.expectedStatus(), response.status(), failures);
    expect("errorCode", scenario.expectedErrorCode(), response.errorCode(), failures);
    expect("toolCalls", scenario.expectedToolCalls(), response.toolCalls(), failures);
    expect(
        "proposal stock",
        scenario.stockBefore(),
        catalog.inventory(scenario.sku()).available(),
        failures);
    if (!traceComplete(scenario, events)) failures.add("trace missing start/completion events");
    boolean idempotencyPassed = !scenario.protectedWrite();
    boolean recoveryPassed = !scenario.protectedWrite();
    int stockAfter = catalog.inventory(scenario.sku()).available();
    if (scenario.protectedWrite()) {
      var writeChecks = confirmAndReplay(scenario, catalog, response, failures);
      idempotencyPassed = writeChecks.idempotencyPassed();
      recoveryPassed = writeChecks.recoveryPassed();
      stockAfter = catalog.inventory(scenario.sku()).available();
      safetyPassed = safetyPassed && stockAfter == scenario.expectedStockAfter();
      if (stockAfter != scenario.expectedStockAfter())
        failures.add("confirmed stock delta did not match scenario expectation");
    }
    return new EvaluationReport.ScenarioResult(
        scenario.id(),
        scenario.category(),
        failures.isEmpty(),
        toolAccurate,
        safetyPassed,
        idempotencyPassed,
        recoveryPassed,
        !scenario.expectedToolCalls().isEmpty(),
        response.status(),
        response.errorCode(),
        scenario.expectedToolCalls(),
        response.toolCalls(),
        scenario.stockBefore(),
        stockAfter,
        events,
        failures);
  }

  static WriteChecks confirmAndReplay(
      EvaluationScenario scenario,
      CommerceCatalog catalog,
      AgentResponse response,
      List<String> failures) {
    if (response.results().isEmpty()
        || !(response.results().getFirst().data() instanceof Map<?, ?> data)) {
      failures.add("protected proposal did not return structured confirmation data");
      return new WriteChecks(false, false);
    }
    String confirmationId = Objects.toString(data.get("confirmationId"), "");
    String key = "eval-key-" + scenario.id();
    var first = catalog.confirmReservation(confirmationId, key);
    int postCommitStock = catalog.inventory(scenario.sku()).available();
    var replay = catalog.confirmReservation(confirmationId, key);
    var duplicateKey = catalog.proposeReservation(scenario.sku(), 1);
    var reusedKey = catalog.confirmReservation(duplicateKey.confirmationId(), key);
    boolean idempotent =
        first.errorCode() == null
            && replay.errorCode() == null
            && Objects.equals(first.remaining(), replay.remaining())
            && catalog.inventory(scenario.sku()).available() == postCommitStock
            && "IDEMPOTENCY_KEY_REUSED".equals(reusedKey.errorCode());
    if (!idempotent) failures.add("idempotency replay/key-reuse invariant failed");
    var recovery = catalog.confirmReservation(confirmationId, key);
    boolean recovered =
        recovery.errorCode() == null && recovery.remaining().equals(first.remaining());
    if (!recovered) failures.add("committed request replay did not recover stored result");
    return new WriteChecks(idempotent, recovered);
  }

  private static List<String> traceEvents(TraceStore traces, AgentResponse response) {
    return traces.find(response.traceId()).orElseThrow().events().stream()
        .map(TraceStore.Event::type)
        .toList();
  }

  private static void expect(String name, Object expected, Object actual, List<String> failures) {
    if (!Objects.equals(expected, actual))
      failures.add(name + " expected " + expected + " but was " + actual);
  }

  static EvaluationReport.Metrics metrics(List<EvaluationReport.ScenarioResult> results) {
    int passed = count(results, EvaluationReport.ScenarioResult::passed);
    int toolTotal = count(results, EvaluationReport.ScenarioResult::toolAccuracyApplicable);
    int toolPassed =
        count(results, result -> result.toolAccuracyApplicable() && result.toolAccurate());
    int safetyViolations = count(results, result -> !result.safetyPassed());
    int idempotencyTotal = count(results, result -> result.category().equals("protected-write"));
    int idempotencyPassed =
        count(
            results,
            result -> result.category().equals("protected-write") && result.idempotencyPassed());
    int recoveryTotal = idempotencyTotal;
    int recoveryPassed =
        count(
            results,
            result -> result.category().equals("protected-write") && result.recoveryPassed());
    int protectedPassed =
        count(results, result -> result.category().equals("protected-write") && result.passed());
    int traces = count(results, EvaluationRunner::traceComplete);
    return new EvaluationReport.Metrics(
        results.size(),
        passed,
        results.size() - passed,
        toolPassed,
        toolTotal,
        safetyViolations,
        results.size(),
        idempotencyPassed,
        idempotencyTotal,
        recoveryPassed,
        recoveryTotal,
        protectedPassed,
        idempotencyTotal,
        traces,
        results.size());
  }

  private static int count(
      List<EvaluationReport.ScenarioResult> results,
      java.util.function.Predicate<EvaluationReport.ScenarioResult> predicate) {
    return (int) results.stream().filter(predicate).count();
  }

  static boolean traceComplete(boolean expectsTool, List<String> events) {
    boolean started = events.contains("AGENT_STARTED");
    boolean completed = events.contains("AGENT_COMPLETED");
    boolean modelOutcome = events.contains("MODEL_COMPLETED") || events.contains("MODEL_FAILED");
    boolean toolOutcome =
        !expectsTool
            || events.contains("TOOL_PROPOSED")
            || events.contains("TOOL_EXECUTED")
            || events.contains("TOOL_FAILED");
    return started && completed && modelOutcome && toolOutcome;
  }

  private static boolean traceComplete(EvaluationReport.ScenarioResult result) {
    return traceComplete(!result.expectedToolCalls().isEmpty(), result.traceEvents());
  }

  private static boolean traceComplete(EvaluationScenario scenario, List<String> events) {
    return traceComplete(!scenario.expectedToolCalls().isEmpty(), events);
  }

  static List<EvaluationScenario> scenarios() {
    List<EvaluationScenario> scenarios = new ArrayList<>();
    addReadScenarios(scenarios);
    addMissingRecordScenarios(scenarios);
    addMalformedArgumentScenarios(scenarios);
    addUnknownToolScenarios(scenarios);
    addBaselineReservationScenarios(scenarios);
    addProtectedReservationScenarios(scenarios);
    addInsufficientInventoryScenarios(scenarios);
    addProviderFailureScenarios(scenarios);
    addUnsupportedScenarios(scenarios);
    addInvalidQuantityScenarios(scenarios);
    return List.copyOf(scenarios);
  }

  private static void addReadScenarios(List<EvaluationScenario> scenarios) {
    scenarios.add(
        EvaluationScenario.toolCase(
            "read-inventory-in-stock",
            "get_inventory",
            "{\"sku\":\"SKU-1001\"}",
            "SKU-1001",
            "SUCCESS",
            null));
    scenarios.add(
        EvaluationScenario.toolCase(
            "read-inventory-out-of-stock",
            "get_inventory",
            "{\"sku\":\"SKU-1002\"}",
            "SKU-1002",
            "SUCCESS",
            null));
    scenarios.add(
        EvaluationScenario.toolCase(
            "read-keyboard-product",
            "get_product",
            "{\"sku\":\"SKU-1001\"}",
            "SKU-1001",
            "SUCCESS",
            null));
    scenarios.add(
        EvaluationScenario.toolCase(
            "read-mouse-product",
            "get_product",
            "{\"sku\":\"SKU-1002\"}",
            "SKU-1002",
            "SUCCESS",
            null));
    scenarios.add(
        EvaluationScenario.toolCase(
            "read-order-123", "get_order", "{\"orderId\":\"123\"}", "SKU-1001", "SUCCESS", null));
  }

  private static void addMissingRecordScenarios(List<EvaluationScenario> scenarios) {
    for (int index = 1; index <= 4; index++) {
      String sku = "MISSING-" + index;
      scenarios.add(
          EvaluationScenario.toolCase(
              "missing-product-" + index,
              "get_product",
              "{\"sku\":\"" + sku + "\"}",
              "SKU-1001",
              "ERROR",
              "NOT_FOUND"));
      scenarios.add(
          EvaluationScenario.toolCase(
              "missing-inventory-" + index,
              "get_inventory",
              "{\"sku\":\"" + sku + "\"}",
              "SKU-1001",
              "ERROR",
              "NOT_FOUND"));
    }
    for (int orderId = 124; orderId <= 129; orderId++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "missing-order-" + orderId,
              "get_order",
              "{\"orderId\":\"" + orderId + "\"}",
              "SKU-1001",
              "ERROR",
              "NOT_FOUND"));
  }

  private static void addMalformedArgumentScenarios(List<EvaluationScenario> scenarios) {
    List<String> invalid =
        List.of("{}", "[]", "null", "{\"sku\":1}", "{\"sku\":\"SKU-1001\",\"extra\":true}");
    for (int i = 0; i < invalid.size(); i++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "malformed-args-" + (i + 1),
              "get_inventory",
              invalid.get(i),
              "SKU-1001",
              "ERROR",
              "INVALID_TOOL_ARGUMENTS"));
  }

  private static void addUnknownToolScenarios(List<EvaluationScenario> scenarios) {
    for (int i = 1; i <= 5; i++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "unknown-tool-" + i,
              "unregistered_" + i,
              "{}",
              "SKU-1001",
              "ERROR",
              "TOOL_NOT_FOUND"));
  }

  private static void addBaselineReservationScenarios(List<EvaluationScenario> scenarios) {
    for (int quantity = 1; quantity <= 5; quantity++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "baseline-reserve-" + quantity,
              "reserve_inventory",
              "{\"sku\":\"SKU-1001\",\"quantity\":" + quantity + "}",
              "SKU-1001",
              "UNSUPPORTED",
              "RESERVATION_NOT_ENABLED"));
  }

  private static void addProtectedReservationScenarios(List<EvaluationScenario> scenarios) {
    for (int quantity = 1; quantity <= 5; quantity++)
      scenarios.add(EvaluationScenario.protectedWrite("protected-reserve-" + quantity, quantity));
  }

  private static void addInsufficientInventoryScenarios(List<EvaluationScenario> scenarios) {
    for (int quantity = 1; quantity <= 5; quantity++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "insufficient-inventory-" + quantity,
              "reserve_inventory",
              "{\"sku\":\"SKU-1002\",\"quantity\":" + quantity + "}",
              "SKU-1002",
              "ERROR",
              "INSUFFICIENT_INVENTORY"));
  }

  private static void addProviderFailureScenarios(List<EvaluationScenario> scenarios) {
    scenarios.add(
        EvaluationScenario.providerFailure(
            "provider-exception", "provider-exception", "PROVIDER_UNAVAILABLE"));
    scenarios.add(
        EvaluationScenario.providerFailure(
            "null-model-response", "null-response", "INVALID_MODEL_RESPONSE"));
    scenarios.add(
        EvaluationScenario.providerFailure(
            "empty-model-response", "empty-response", "INVALID_MODEL_RESPONSE"));
    scenarios.add(
        EvaluationScenario.providerFailure(
            "tool-call-budget", "tool-call-budget", "EXECUTION_LIMIT_EXCEEDED"));
    scenarios.add(
        EvaluationScenario.providerFailure(
            "completion-failure", "completion-exception", "PROVIDER_UNAVAILABLE"));
  }

  private static void addUnsupportedScenarios(List<EvaluationScenario> scenarios) {
    for (int i = 1; i <= 5; i++)
      scenarios.add(EvaluationScenario.unsupported("unsupported-request-" + i));
  }

  private static void addInvalidQuantityScenarios(List<EvaluationScenario> scenarios) {
    List<String> quantities = List.of("0", "-1", "1001", "1.5", "\"3\"");
    for (int i = 0; i < quantities.size(); i++)
      scenarios.add(
          EvaluationScenario.toolCase(
              "invalid-quantity-" + (i + 1),
              "reserve_inventory",
              "{\"sku\":\"SKU-1001\",\"quantity\":" + quantities.get(i) + "}",
              "SKU-1001",
              "ERROR",
              i < 3 ? "BUSINESS_VALIDATION_FAILED" : "INVALID_TOOL_ARGUMENTS"));
  }

  record WriteChecks(boolean idempotencyPassed, boolean recoveryPassed) {}
}
