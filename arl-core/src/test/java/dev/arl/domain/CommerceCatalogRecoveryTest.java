package dev.arl.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommerceCatalogRecoveryTest {
  @TempDir Path temp;

  @Test
  void expiredConfirmationNeverChangesInventory() throws Exception {
    Path database = temp.resolve("expired-confirmation.db");
    try (var catalog = CommerceCatalog.fileBacked(database.toString())) {
      var proposal = catalog.proposeReservation("SKU-1001", 3);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement =
              connection.prepareStatement(
                  "update confirmations set expires_at_epoch=0 where confirmation_id=?")) {
        statement.setString(1, proposal.confirmationId());
        assertEquals(1, statement.executeUpdate());
      }

      var result = catalog.confirmReservation(proposal.confirmationId(), "expired-key-123");

      assertEquals("CONFIRMATION_EXPIRED", result.errorCode());
      assertEquals(20, catalog.inventory("SKU-1001").available());
    }
  }
}
