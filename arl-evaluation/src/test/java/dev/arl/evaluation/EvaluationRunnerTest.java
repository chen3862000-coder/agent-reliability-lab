package dev.arl.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arl.agent.AgentResponse;
import dev.arl.domain.CommerceCatalog;
import dev.arl.observability.TraceStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationRunnerTest {
  @TempDir Path temp;

  @Test
  void offlineEvaluationProducesHonestMetricsAndReport() throws Exception {
    Path reportFile = temp.resolve("report.json");
    EvaluationReport report = new EvaluationRunner().write(reportFile);

    assertEquals(59, report.metrics().total());
    assertEquals(59, report.metrics().passed());
    assertEquals(0, report.metrics().failed());
    assertEquals(0, report.metrics().safetyViolations());
    assertEquals(5, report.metrics().protectedWritePassed());
    assertEquals(44, report.metrics().routingContractMatchTotal());
    assertEquals(44, report.metrics().routingContractMatchPassed());
    assertEquals(59, report.metrics().traceCompletenessPassed());
    assertEquals(5, report.metrics().idempotencyPassed());
    assertEquals(5, report.metrics().recoveryPassed());
    assertEquals(
        54,
        report.scenarios().stream()
            .filter(result -> !result.idempotencyApplicable() && !result.idempotencyPassed())
            .count());
    assertEquals(
        54,
        report.scenarios().stream()
            .filter(result -> !result.recoveryApplicable() && !result.recoveryPassed())
            .count());
    assertEquals(
        5,
        report.scenarios().stream()
            .filter(result -> result.idempotencyApplicable() && result.recoveryApplicable())
            .count());
    assertTrue(report.scenarios().stream().allMatch(EvaluationReport.ScenarioResult::passed));
    assertEquals(
        59,
        report.scenarios().stream().map(EvaluationReport.ScenarioResult::id).distinct().count());
    assertEquals(
        10,
        report.scenarios().stream()
            .map(EvaluationReport.ScenarioResult::category)
            .distinct()
            .count());

    JsonNode json = new ObjectMapper().readTree(reportFile.toFile());
    assertEquals("offline-m4", json.path("suite").asText());
    assertEquals(59, json.path("scenarios").size());
    assertTrue(
        json.path("metricDefinitions")
            .path("routingContractMatch")
            .asText()
            .contains("do not measure autonomous model routing quality"));
  }

  @Test
  void malformedProtectedProposalFailsWithoutAttemptingConfirmation() {
    try (var catalog = new CommerceCatalog()) {
      var scenario = EvaluationScenario.protectedWrite("bad-proposal", 2);
      var traces = new TraceStore(8);
      traces.save(
          new TraceStore.Trace(
              "trace",
              "deepseek",
              "test-model",
              List.of(
                  new TraceStore.Event(
                      java.time.Instant.now(), "AGENT_STARTED", null, "RUNNING", null, 0),
                  new TraceStore.Event(
                      java.time.Instant.now(), "MODEL_COMPLETED", null, "SUCCESS", null, 1),
                  new TraceStore.Event(
                      java.time.Instant.now(),
                      "TOOL_PROPOSED",
                      "reserve_inventory",
                      "PROPOSED",
                      null,
                      0),
                  new TraceStore.Event(
                      java.time.Instant.now(),
                      "TOOL_EXECUTED",
                      "reserve_inventory",
                      "SUCCESS",
                      null,
                      0),
                  new TraceStore.Event(
                      java.time.Instant.now(), "AGENT_COMPLETED", null, "SUCCESS", null, 2))));
      var response =
          new AgentResponse(
              "SUCCESS",
              "missing data",
              null,
              "trace",
              "deepseek",
              "test-model",
              List.of("reserve_inventory"),
              List.of());
      var result = new EvaluationRunner().assess(scenario, catalog, traces, response);

      assertFalse(result.passed());
      assertEquals(20, catalog.inventory("SKU-1001").available());
    }
  }

  @Test
  void failureAndTraceHelpersExerciseFailClosedBranches() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvaluationRunner.failureResponse(
                EvaluationScenario.providerFailure("bad-fault", "other", "ERROR")));
    assertFalse(
        EvaluationRunner.traceComplete(false, false, List.of("AGENT_STARTED", "AGENT_COMPLETED")));
    assertFalse(
        EvaluationRunner.traceComplete(
            true,
            false,
            List.of("AGENT_STARTED", "MODEL_COMPLETED", "TOOL_PROPOSED", "AGENT_COMPLETED")));
    assertTrue(
        EvaluationRunner.traceComplete(
            true,
            false,
            List.of("AGENT_STARTED", "MODEL_COMPLETED", "TOOL_EXECUTED", "AGENT_COMPLETED")));
    assertTrue(
        EvaluationRunner.traceComplete(
            true,
            false,
            List.of("AGENT_STARTED", "MODEL_COMPLETED", "TOOL_FAILED", "AGENT_COMPLETED")));
    assertTrue(
        EvaluationRunner.traceComplete(
            false,
            true,
            List.of("AGENT_STARTED", "MODEL_FAILED", "TOOL_PROPOSED", "AGENT_COMPLETED")));
  }

  @Test
  void incompleteLifecycleAndToolTraceAreRejected() {
    assertFalse(
        EvaluationRunner.traceComplete(true, false, List.of("AGENT_STARTED", "MODEL_COMPLETED")));
    assertFalse(
        EvaluationRunner.traceComplete(
            true, false, List.of("AGENT_STARTED", "AGENT_COMPLETED", "MODEL_FAILED")));
    assertFalse(
        EvaluationRunner.traceComplete(
            false, true, List.of("AGENT_STARTED", "MODEL_FAILED", "AGENT_COMPLETED")));
  }

  @Test
  void routingContractMetricUsesOnlyApplicableCasesAndCountsMismatches() {
    var mismatch =
        new EvaluationReport.ScenarioResult(
            "wrong-tool",
            "tool-selection",
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            true,
            "SUCCESS",
            null,
            List.of("get_inventory"),
            List.of("get_product"),
            20,
            20,
            List.of("AGENT_STARTED", "MODEL_COMPLETED", "AGENT_COMPLETED"),
            List.of("tool calls differed"));
    var notApplicable =
        new EvaluationReport.ScenarioResult(
            "no-tool",
            "provider-failure",
            true,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            "ERROR",
            "PROVIDER_UNAVAILABLE",
            List.of(),
            List.of(),
            20,
            20,
            List.of("AGENT_STARTED", "MODEL_FAILED", "AGENT_COMPLETED"),
            List.of());

    var metrics = EvaluationRunner.metrics(List.of(mismatch, notApplicable));

    assertEquals(0, metrics.routingContractMatchPassed());
    assertEquals(1, metrics.routingContractMatchTotal());
  }
}
