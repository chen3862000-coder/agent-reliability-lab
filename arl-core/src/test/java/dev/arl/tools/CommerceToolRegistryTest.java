package dev.arl.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arl.domain.CommerceCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommerceToolRegistryTest {
  private final CommerceCatalog catalog = new CommerceCatalog();
  private final CommerceToolRegistry tools = new CommerceToolRegistry(catalog);

  @Test
  void readToolsReturnKnownSyntheticRecords() {
    assertEquals(
        new CommerceCatalog.Product("SKU-1001", "Mechanical Keyboard", 7900, "USD"),
        tools.execute("get_product", "{\"sku\":\"SKU-1001\"}").data());
    assertEquals(
        new CommerceCatalog.Inventory("SKU-1001", 20),
        tools.execute("get_inventory", "{\"sku\":\"SKU-1001\"}").data());
    assertEquals(
        new CommerceCatalog.Order("123", "SKU-1001", 5, "PROCESSING"),
        tools.execute("get_order", "{\"orderId\":\"123\"}").data());
    assertEquals(
        0,
        ((CommerceCatalog.Inventory)
                tools.execute("get_inventory", "{\"sku\":\"SKU-1002\"}").data())
            .available());
    assertNull(tools.execute("get_product", "{\"sku\":\"SKU-1002\"}").errorCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "null",
        "",
        "broken",
        "{\"sku\":2}",
        "{\"sku\":null}",
        "{\"sku\":\"\"}",
        "{\"sku\":\" secret \"}",
        "{\"sku\":\"SKU-1001\",\"extra\":true}",
        "{\"other\":\"SKU-1001\"}",
        "{\"sku\":\"SKU-1001\",\"sku\":\"SKU-1002\"}",
        "{\"sku\":\"SKU-1001\"}{}"
      })
  void invalidArgumentsNeverReachARead(String json) {
    assertEquals("INVALID_TOOL_ARGUMENTS", tools.execute("get_inventory", json).errorCode());
  }

  @Test
  void absentAndOversizedArgumentsAndUnknownToolsAreRejected() {
    assertEquals("INVALID_TOOL_ARGUMENTS", tools.execute("get_inventory", null).errorCode());
    assertEquals(
        "INVALID_TOOL_ARGUMENTS", tools.execute("get_inventory", "x".repeat(4097)).errorCode());
    assertEquals("TOOL_NOT_FOUND", tools.execute("track_dhl", "{}").errorCode());
    assertEquals("NOT_FOUND", tools.execute("get_product", "{\"sku\":\"MISSING\"}").errorCode());
    assertEquals("NOT_FOUND", tools.execute("get_inventory", "{\"sku\":\"MISSING\"}").errorCode());
    assertEquals("NOT_FOUND", tools.execute("get_order", "{\"orderId\":\"MISSING\"}").errorCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-5", "1001"})
  void reservationConstraintsRejectBadQuantities(String quantity) {
    assertEquals(
        "BUSINESS_VALIDATION_FAILED",
        tools
            .execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":" + quantity + "}")
            .errorCode());
    assertEquals(20, catalog.inventory("SKU-1001").available());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "1.5", "\"3\"", "2147483648"})
  void reservationRejectsNonIntegerQuantities(String quantity) {
    assertEquals(
        "INVALID_TOOL_ARGUMENTS",
        tools
            .execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":" + quantity + "}")
            .errorCode());
  }

  @Test
  void proposalsNeverChangeInventoryOrPretendToAuthorize() {
    for (int i = 0; i < 2; i++) {
      var result = tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":3}");
      assertEquals("RESERVATION_NOT_ENABLED", result.errorCode());
      assertNotNull(result.data());
      assertEquals(20, catalog.inventory("SKU-1001").available());
    }
    assertEquals(
        "INSUFFICIENT_INVENTORY",
        tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":21}").errorCode());
    assertEquals(
        "NOT_FOUND",
        tools.execute("reserve_inventory", "{\"sku\":\"MISSING\",\"quantity\":1}").errorCode());
    assertEquals(
        "INVALID_TOOL_ARGUMENTS",
        tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"other\":1}").errorCode());
  }

  @Test
  void protectedReservationRequiresConfirmationBeforeMutatingStock() {
    var proposal =
        tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":3}", true);
    assertNull(proposal.errorCode());
    assertEquals(20, catalog.inventory("SKU-1001").available());

    @SuppressWarnings("unchecked")
    var data = (java.util.Map<String, Object>) proposal.data();
    var committed = catalog.confirmReservation(data.get("confirmationId").toString(), "idem-12345");

    assertNull(committed.errorCode());
    assertEquals(17, committed.remaining());
    assertEquals(17, catalog.inventory("SKU-1001").available());
  }

  @Test
  void confirmationIsIdempotentAndRejectsKeyReuseForChangedOperation() {
    var first = tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":2}", true);
    var second = tools.execute("reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":4}", true);
    @SuppressWarnings("unchecked")
    var firstData = (java.util.Map<String, Object>) first.data();
    @SuppressWarnings("unchecked")
    var secondData = (java.util.Map<String, Object>) second.data();

    var committed =
        catalog.confirmReservation(firstData.get("confirmationId").toString(), "idem-abcde");
    var replayed =
        catalog.confirmReservation(firstData.get("confirmationId").toString(), "idem-abcde");
    var reused =
        catalog.confirmReservation(secondData.get("confirmationId").toString(), "idem-abcde");

    assertNull(committed.errorCode());
    assertEquals(committed.remaining(), replayed.remaining());
    assertEquals("IDEMPOTENCY_KEY_REUSED", reused.errorCode());
    assertEquals(18, catalog.inventory("SKU-1001").available());
  }

  @Test
  void fileBackedCatalogPreservesStockAndIdempotencyAcrossRestart(@TempDir Path temp) {
    Path db = temp.resolve("arl.db");
    String confirmationId;
    try (var first = CommerceCatalog.fileBacked(db.toString())) {
      var proposal = first.proposeReservation("SKU-1001", 5);
      confirmationId = proposal.confirmationId();
      var committed = first.confirmReservation(confirmationId, "restart-key-1");
      assertNull(committed.errorCode());
      assertEquals(15, first.inventory("SKU-1001").available());
    }

    try (var restarted = CommerceCatalog.fileBacked(db.toString())) {
      assertEquals(15, restarted.inventory("SKU-1001").available());
      var replayed = restarted.confirmReservation(confirmationId, "restart-key-1");
      assertNull(replayed.errorCode());
      assertEquals(15, replayed.remaining());
      assertEquals(15, restarted.inventory("SKU-1001").available());
    }
  }

  @Test
  void concurrentConfirmationsApplyOnlyOneStockMutation() throws Exception {
    var proposal = catalog.proposeReservation("SKU-1001", 7);
    var ready = new CountDownLatch(6);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks =
          java.util.stream.IntStream.range(0, 6)
              .<java.util.concurrent.Callable<CommerceCatalog.ReservationCommit>>mapToObj(
                  i ->
                      () -> {
                        ready.countDown();
                        start.await();
                        return catalog.confirmReservation(proposal.confirmationId(), "key-" + i);
                      })
              .toList();
      var futures = tasks.stream().map(pool::submit).toList();
      ready.await();
      start.countDown();

      var results = new ArrayList<CommerceCatalog.ReservationCommit>();
      for (var future : futures) results.add(future.get());

      assertEquals(1, results.stream().filter(r -> r.errorCode() == null).count());
      assertEquals(
          5,
          results.stream().filter(r -> "CONFIRMATION_ALREADY_USED".equals(r.errorCode())).count());
      assertEquals(13, catalog.inventory("SKU-1001").available());
    }
  }

  @Test
  void contractsAndCallbacksExposeStructuredResults() throws Exception {
    var observed = new ArrayList<ToolResult>();
    var callbacks = tools.callbacks(observed::add);
    assertEquals(4, callbacks.length);
    var mapper = new ObjectMapper();
    for (var contract : tools.contracts()) {
      assertFalse(mapper.readTree(contract.inputSchema()).path("additionalProperties").asBoolean());
      assertEquals("object", mapper.readTree(contract.outputSchema()).path("type").asText());
      assertEquals(0, contract.maxRetries());
      assertEquals("LOCAL_IN_MEMORY_NO_IO", contract.timeoutPolicy());
      assertNotNull(contract.authorization());
      assertNotNull(contract.sideEffect());
    }
    var result = mapper.readTree(callbacks[0].call("{\"sku\":\"SKU-1001\"}"));
    assertEquals("get_product", callbacks[0].getToolDefinition().name());
    assertEquals("Mechanical Keyboard", result.path("data").path("name").asText());
    assertEquals(1, observed.size());
    assertThrows(UnsupportedOperationException.class, () -> tools.contracts().clear());
  }
}
