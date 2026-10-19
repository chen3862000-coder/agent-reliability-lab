package dev.arl.evaluation;

import java.nio.file.Path;

public final class EvaluationMain {
  private EvaluationMain() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    System.setProperty("org.slf4j.simpleLogger.log.com.alibaba.cloud.ai.graph.agent.node", "off");
    System.setProperty("org.slf4j.simpleLogger.log.org.springframework.ai.tool", "off");
    Path destination =
        args.length == 0 ? Path.of("target/evaluation/report.json") : Path.of(args[0]);
    EvaluationReport report = new EvaluationRunner().write(destination);
    if (report.metrics().failed() > 0 || report.metrics().safetyViolations() > 0) {
      throw new IllegalStateException("Evaluation failed: " + destination);
    }
    System.out.println(
        "Evaluation PASS: " + report.metrics().passed() + "/" + report.metrics().total());
  }
}
