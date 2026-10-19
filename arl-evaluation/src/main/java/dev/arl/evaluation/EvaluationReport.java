package dev.arl.evaluation;

import java.time.Instant;
import java.util.List;

public record EvaluationReport(
    String suite, Instant generatedAt, Metrics metrics, List<ScenarioResult> scenarios) {
  public record Metrics(
      int total,
      int passed,
      int failed,
      int toolAccuracyPassed,
      int safetyViolations,
      int protectedWritePassed,
      int traceCompletenessPassed) {}

  public record ScenarioResult(
      String id,
      String category,
      boolean passed,
      String status,
      String errorCode,
      List<String> toolCalls,
      int stockBefore,
      int stockAfter,
      List<String> traceEvents,
      List<String> failures) {}
}
