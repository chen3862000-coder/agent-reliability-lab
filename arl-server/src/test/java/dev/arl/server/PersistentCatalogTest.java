package dev.arl.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.arl.domain.CommerceCatalog;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class PersistentCatalogTest {
  @TempDir Path temp;

  @Test
  void configuredSqlitePathSurvivesApplicationRestart() {
    Path db = temp.resolve("arl-server.db");
    String confirmationId;
    try (var first = context(db)) {
      var catalog = first.getBean(CommerceCatalog.class);
      var proposal = catalog.proposeReservation("SKU-1001", 4);
      confirmationId = proposal.confirmationId();
      var committed = catalog.confirmReservation(confirmationId, "server-restart-key");
      assertNull(committed.errorCode());
      assertEquals(16, catalog.inventory("SKU-1001").available());
    }

    try (var restarted = context(db)) {
      var catalog = restarted.getBean(CommerceCatalog.class);
      assertEquals(16, catalog.inventory("SKU-1001").available());
      assertEquals(
          16, catalog.confirmReservation(confirmationId, "server-restart-key").remaining());
    }
  }

  private static org.springframework.context.ConfigurableApplicationContext context(Path db) {
    return new SpringApplicationBuilder(ArlApplication.class)
        .web(WebApplicationType.NONE)
        .run("--arl.db.path=" + db, "--arl.llm.deepseek.api-key=", "--arl.llm.grok.api-key=");
  }
}
