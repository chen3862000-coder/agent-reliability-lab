package dev.arl.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
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
}
