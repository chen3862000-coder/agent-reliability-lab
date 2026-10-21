package dev.arl.evaluation;

import java.util.List;

record EvaluationScenario(
    String id,
    String category,
    String message,
    String mode,
    String tool,
    String arguments,
    String expectedStatus,
    String expectedErrorCode,
    List<String> expectedToolCalls,
    String sku,
    int stockBefore,
    int expectedStockAfter,
    String failureKind,
    boolean protectedWrite) {
  static EvaluationScenario toolCase(
      String id, String tool, String arguments, String sku, String expectedStatus, String error) {
    List<String> expectedTools =
        tool == null || "TOOL_NOT_FOUND".equals(error) ? List.of() : List.of(tool);
    return new EvaluationScenario(
        id,
        category(id),
        id,
        "BASELINE",
        tool,
        arguments,
        expectedStatus,
        error,
        expectedTools,
        sku,
        initialStock(sku),
        initialStock(sku),
        null,
        false);
  }

  static EvaluationScenario providerFailure(
      String id, String failureKind, String expectedErrorCode) {
    return new EvaluationScenario(
        id,
        "provider-failure",
        failureKind,
        "BASELINE",
        null,
        null,
        "ERROR",
        expectedErrorCode,
        List.of(),
        "SKU-1001",
        20,
        20,
        failureKind,
        false);
  }

  static EvaluationScenario protectedWrite(String id, int quantity) {
    return new EvaluationScenario(
        id,
        "protected-write",
        id,
        "PROTECTED",
        "reserve_inventory",
        "{\"sku\":\"SKU-1001\",\"quantity\":" + quantity + "}",
        "SUCCESS",
        null,
        List.of("reserve_inventory"),
        "SKU-1001",
        20,
        20 - quantity,
        null,
        true);
  }

  static EvaluationScenario unsupported(String id) {
    return new EvaluationScenario(
        id,
        "unsupported",
        id,
        "BASELINE",
        null,
        null,
        "UNSUPPORTED",
        "UNSUPPORTED_REQUEST",
        List.of(),
        "SKU-1001",
        20,
        20,
        null,
        false);
  }

  private static int initialStock(String sku) {
    return "SKU-1002".equals(sku) ? 0 : 20;
  }

  private static String category(String id) {
    String category = "validation";
    if (id.startsWith("read-")) category = "read-contract";
    else if (id.startsWith("missing-")) category = "missing-record";
    else if (id.startsWith("malformed-args-")) category = "malformed-arguments";
    else if (id.startsWith("unknown-tool-")) category = "unknown-tool-rejection";
    else if (id.startsWith("baseline-reserve-")) category = "baseline-write-guard";
    else if (id.startsWith("insufficient-inventory-")) category = "insufficient-inventory";
    else if (id.startsWith("invalid-quantity-")) category = "invalid-quantity";
    return category;
  }
}
