package dev.arl.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationRunnerTest {
  @TempDir Path temp;

  @Test
  void offlineEvaluationProducesHonestMetricsAndReport() throws Exception {
    Path reportFile = temp.resolve("report.json");
    EvaluationReport report = new EvaluationRunner().write(reportFile);

    assertEquals(10, report.metrics().total());
    assertEquals(10, report.metrics().passed());
    assertEquals(0, report.metrics().failed());
    assertEquals(0, report.metrics().safetyViolations());
    assertEquals(1, report.metrics().protectedWritePassed());
    assertTrue(report.scenarios().stream().allMatch(EvaluationReport.ScenarioResult::passed));

    JsonNode json = new ObjectMapper().readTree(reportFile.toFile());
    assertEquals("offline-m3", json.path("suite").asText());
    assertEquals(10, json.path("scenarios").size());
  }
}
