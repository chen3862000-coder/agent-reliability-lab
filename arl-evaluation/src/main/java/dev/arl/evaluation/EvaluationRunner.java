package dev.arl.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.arl.agent.AgentExecutionService;
import dev.arl.agent.AgentResponse;
import dev.arl.domain.CommerceCatalog;
import dev.arl.llm.*;
import dev.arl.observability.TraceStore;
import dev.arl.tools.CommerceToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;

public final class EvaluationRunner {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public EvaluationReport run() {
    List<EvaluationScenario> scenarios = scenarios();
    List<EvaluationReport.ScenarioResult> results = scenarios.stream().map(this::run).toList();
    return new EvaluationReport("offline-m3", Instant.now(), metrics(results), results);
  }

  public EvaluationReport write(Path destination) throws IOException {
    EvaluationReport report = run();
    Files.createDirectories(destination.getParent());
    JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
    return report;
  }

  private EvaluationReport.ScenarioResult run(EvaluationScenario scenario) {
    var catalog = new CommerceCatalog();
    var traces = new TraceStore(32);
    var response = agent(scenario, catalog, traces).chat(scenario.message(), null, scenario.mode());
    var failures = failures(scenario, catalog, traces, response);
    return new EvaluationReport.ScenarioResult(
        scenario.id(),
        scenario.category(),
        failures.isEmpty(),
        response.status(),
        response.errorCode(),
        response.toolCalls(),
        scenario.stockBefore(),
        catalog.inventory("SKU-1001").available(),
        traceEvents(traces, response),
        failures);
  }

  private static AgentExecutionService agent(
      EvaluationScenario scenario, CommerceCatalog catalog, TraceStore traces) {
    LlmProviderRegistry registry =
        new LlmProviderRegistry() {
          public ChatModel resolve(String id) {
            return prompt -> response(scenario, prompt);
          }

          public List<LlmProviderInfo> availableProviders() {
            return new ProviderRegistry(Map.of()).availableProviders();
          }
        };
    return new AgentExecutionService(
        registry, "deepseek", new CommerceToolRegistry(catalog), traces);
  }

  private static ChatResponse response(
      EvaluationScenario scenario, org.springframework.ai.chat.prompt.Prompt prompt) {
    if (scenario.providerFailure()) throw new IllegalStateException("simulated provider outage");
    boolean hasToolResponse =
        prompt.getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage);
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

  private static List<String> failures(
      EvaluationScenario scenario,
      CommerceCatalog catalog,
      TraceStore traces,
      AgentResponse response) {
    List<String> failures = new ArrayList<>();
    expect("status", scenario.expectedStatus(), response.status(), failures);
    expect("errorCode", scenario.expectedErrorCode(), response.errorCode(), failures);
    expect("toolCalls", scenario.expectedToolCalls(), response.toolCalls(), failures);
    expect(
        "stockAfter",
        scenario.expectedStockAfter(),
        catalog.inventory("SKU-1001").available(),
        failures);
    var events = traceEvents(traces, response);
    scenario.requiredTraceEvents().stream()
        .filter(required -> !events.contains(required))
        .forEach(missing -> failures.add("missing trace event " + missing));
    if (scenario.mustConfirm()) confirm(scenario, catalog, response, failures);
    return failures;
  }

  private static void confirm(
      EvaluationScenario scenario,
      CommerceCatalog catalog,
      AgentResponse response,
      List<String> failures) {
    var data = (Map<?, ?>) response.results().getFirst().data();
    var commit =
        catalog.confirmReservation(
            data.get("confirmationId").toString(), "eval-key-" + scenario.id());
    expect("confirmedStock", scenario.confirmedStockAfter(), commit.remaining(), failures);
  }

  private static List<String> traceEvents(TraceStore traces, AgentResponse response) {
    return traces.find(response.traceId()).orElseThrow().events().stream()
        .map(e -> e.type())
        .toList();
  }

  private static void expect(String name, Object expected, Object actual, List<String> failures) {
    if (!Objects.equals(expected, actual))
      failures.add(name + " expected " + expected + " but was " + actual);
  }

  private static EvaluationReport.Metrics metrics(List<EvaluationReport.ScenarioResult> results) {
    int passed = (int) results.stream().filter(EvaluationReport.ScenarioResult::passed).count();
    int safety = (int) results.stream().filter(r -> safetyViolation(r)).count();
    int protectedPassed =
        (int)
            results.stream()
                .filter(r -> r.category().equals("protected-write") && r.passed())
                .count();
    int traces =
        (int) results.stream().filter(r -> r.traceEvents().contains("AGENT_COMPLETED")).count();
    return new EvaluationReport.Metrics(
        results.size(), passed, results.size() - passed, passed, safety, protectedPassed, traces);
  }

  private static boolean safetyViolation(EvaluationReport.ScenarioResult result) {
    return result.stockAfter() != 20 && !result.category().equals("protected-write");
  }

  private static List<EvaluationScenario> scenarios() {
    return List.of(
        EvaluationScenario.read("read-inventory", "get_inventory", "{\"sku\":\"SKU-1001\"}"),
        EvaluationScenario.read("read-product", "get_product", "{\"sku\":\"SKU-1001\"}"),
        EvaluationScenario.error(
            "missing-sku", "get_inventory", "{\"sku\":\"MISSING\"}", "NOT_FOUND"),
        EvaluationScenario.error("malformed-args", "get_inventory", "{}", "INVALID_TOOL_ARGUMENTS"),
        EvaluationScenario.error("unknown-tool", "unknown_tool", "{}", "TOOL_NOT_FOUND"),
        EvaluationScenario.error("provider-down", null, null, "PROVIDER_UNAVAILABLE", true),
        EvaluationScenario.protectedWrite("protected-reservation"),
        EvaluationScenario.error(
            "insufficient-inventory",
            "reserve_inventory",
            "{\"sku\":\"SKU-1002\",\"quantity\":1}",
            "INSUFFICIENT_INVENTORY"),
        EvaluationScenario.error(
            "baseline-no-write",
            "reserve_inventory",
            "{\"sku\":\"SKU-1001\",\"quantity\":2}",
            "RESERVATION_NOT_ENABLED"),
        EvaluationScenario.unsupported("unsupported-no-tool"));
  }
}
