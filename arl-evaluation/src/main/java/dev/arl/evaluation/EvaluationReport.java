package dev.arl.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationReport(
    String suite,
    Instant generatedAt,
    Map<String, String> metricDefinitions,
    Metrics metrics,
    List<ScenarioResult> scenarios) {
  public record Metrics(
      int total,
      int passed,
      int failed,
      int routingContractMatchPassed,
      int routingContractMatchTotal,
      int safetyViolations,
      int safetyChecks,
      int idempotencyPassed,
      int idempotencyTotal,
      int recoveryPassed,
      int recoveryTotal,
      int protectedWritePassed,
      int protectedWriteTotal,
      int traceCompletenessPassed,
      int traceCompletenessTotal) {}

  public record ScenarioResult(
      String id,
      String category,
      boolean passed,
      boolean routingContractMatched,
      boolean safetyPassed,
      boolean idempotencyApplicable,
      boolean idempotencyPassed,
      boolean recoveryApplicable,
      boolean recoveryPassed,
      boolean routingContractApplicable,
      String status,
      String errorCode,
      List<String> expectedToolCalls,
      List<String> toolCalls,
      int stockBefore,
      int stockAfter,
      List<String> traceEvents,
      List<String> failures) {}
}
