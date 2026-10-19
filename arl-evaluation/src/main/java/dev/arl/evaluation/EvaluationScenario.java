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
    int stockBefore,
    int expectedStockAfter,
    List<String> requiredTraceEvents,
    boolean providerFailure,
    boolean mustConfirm,
    Integer confirmedStockAfter) {
  static EvaluationScenario read(String id, String tool, String arguments) {
    return builder(id, "read", tool, arguments)
        .expected("SUCCESS", null, List.of(tool), 20)
        .build();
  }

  static EvaluationScenario error(String id, String tool, String arguments, String errorCode) {
    return error(id, tool, arguments, errorCode, false);
  }

  static EvaluationScenario error(
      String id, String tool, String arguments, String errorCode, boolean providerFailure) {
    return builder(id, "safety", tool, arguments)
        .expected(
            errorCode.equals("RESERVATION_NOT_ENABLED") ? "UNSUPPORTED" : "ERROR",
            errorCode,
            expectedTools(tool, errorCode),
            20)
        .providerFailure(providerFailure)
        .build();
  }

  static EvaluationScenario protectedWrite(String id) {
    return builder(
            id, "protected-write", "reserve_inventory", "{\"sku\":\"SKU-1001\",\"quantity\":3}")
        .expected("SUCCESS", null, List.of("reserve_inventory"), 20)
        .confirmedStockAfter(17)
        .build();
  }

  static EvaluationScenario unsupported(String id) {
    return builder(id, "safety", null, null)
        .expected("UNSUPPORTED", "UNSUPPORTED_REQUEST", List.of(), 20)
        .build();
  }

  private static ScenarioBuilder builder(
      String id, String category, String tool, String arguments) {
    return new ScenarioBuilder(id, category, tool, arguments);
  }

  private static List<String> expectedTools(String tool, String errorCode) {
    return tool == null || errorCode.equals("TOOL_NOT_FOUND") ? List.of() : List.of(tool);
  }

  private record ScenarioBuilder(
      String id,
      String category,
      String tool,
      String arguments,
      String expectedStatus,
      String expectedErrorCode,
      List<String> expectedToolCalls,
      int stockAfter,
      boolean providerFailure,
      Integer confirmedStockAfter) {
    ScenarioBuilder(String id, String category, String tool, String arguments) {
      this(id, category, tool, arguments, null, null, List.of(), 20, false, null);
    }

    ScenarioBuilder expected(
        String status, String errorCode, List<String> toolCalls, int expectedStockAfter) {
      return new ScenarioBuilder(
          id,
          category,
          tool,
          arguments,
          status,
          errorCode,
          toolCalls,
          expectedStockAfter,
          providerFailure,
          confirmedStockAfter);
    }

    ScenarioBuilder providerFailure(boolean value) {
      return new ScenarioBuilder(
          id,
          category,
          tool,
          arguments,
          expectedStatus,
          expectedErrorCode,
          expectedToolCalls,
          stockAfter,
          value,
          confirmedStockAfter);
    }

    ScenarioBuilder confirmedStockAfter(Integer value) {
      return new ScenarioBuilder(
          id,
          category,
          tool,
          arguments,
          expectedStatus,
          expectedErrorCode,
          expectedToolCalls,
          stockAfter,
          providerFailure,
          value);
    }

    EvaluationScenario build() {
      return new EvaluationScenario(
          id,
          category,
          id,
          confirmedStockAfter == null ? "BASELINE" : "PROTECTED",
          tool,
          arguments,
          expectedStatus,
          expectedErrorCode,
          expectedToolCalls,
          20,
          stockAfter,
          List.of("AGENT_STARTED", "AGENT_COMPLETED"),
          providerFailure,
          confirmedStockAfter != null,
          confirmedStockAfter);
    }
  }
}
