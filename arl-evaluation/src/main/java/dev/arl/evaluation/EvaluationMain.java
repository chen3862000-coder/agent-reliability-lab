package dev.arl.evaluation;

import java.nio.file.Path;

/** Command-line entry point for deterministic offline evaluation. */
public final class EvaluationMain {
  private EvaluationMain() {}

  /**
   * Writes the report to the first argument, or {@code target/evaluation/report.json} by default.
   * Exits with an exception if any scenario fails or reports a safety violation.
   */
  public static void main(String[] args) throws Exception {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    System.setProperty("org.slf4j.simpleLogger.log.com.alibaba.cloud.ai.graph.agent.node", "off");
    System.setProperty("org.slf4j.simpleLogger.log.org.springframework.ai.tool", "off");
    Path destination =
        args.length == 0 ? Path.of("target/evaluation/report.json") : Path.of(args[0]);
    EvaluationReport report = new EvaluationRunner().write(destination);
    validate(report, destination);
    System.out.println(
        "Evaluation PASS: " + report.metrics().passed() + "/" + report.metrics().total());
  }

  static void validate(EvaluationReport report, Path destination) {
    if (report.metrics().failed() > 0 || report.metrics().safetyViolations() > 0)
      throw new IllegalStateException("Evaluation failed: " + destination);
  }
}
