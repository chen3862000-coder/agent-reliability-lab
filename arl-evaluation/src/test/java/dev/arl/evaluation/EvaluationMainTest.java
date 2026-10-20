package dev.arl.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationMainTest {
  @TempDir Path temp;

  @Test
  void commandLineEntryPointWritesReport() throws Exception {
    Path report = temp.resolve("cli-report.json");
    EvaluationMain.main(new String[] {report.toString()});
    assertTrue(java.nio.file.Files.exists(report));
  }

  @Test
  void failedScenariosFailClosed() {
    var report = report(new EvaluationReport.Metrics(1, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1));

    assertThrows(
        IllegalStateException.class,
        () -> EvaluationMain.validate(report, temp.resolve("failed.json")));
  }

  @Test
  void safetyViolationsFailClosed() {
    var report = report(new EvaluationReport.Metrics(1, 1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1));

    assertThrows(
        IllegalStateException.class,
        () -> EvaluationMain.validate(report, temp.resolve("unsafe.json")));
  }

  private static EvaluationReport report(EvaluationReport.Metrics metrics) {
    return new EvaluationReport("test", Instant.EPOCH, Map.of(), metrics, List.of());
  }
}
