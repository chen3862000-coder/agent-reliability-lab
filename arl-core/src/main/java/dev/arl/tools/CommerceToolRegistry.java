package dev.arl.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.arl.domain.CommerceCatalog;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Central tool contracts for validated reads and confirmation-gated reservation proposals. */
public final class CommerceToolRegistry {
  public record Contract(
      String name,
      String description,
      String inputSchema,
      String outputSchema,
      String sideEffect,
      String authorization,
      String timeoutPolicy,
      int maxRetries) {}

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String OUTPUT =
      """
      {"type":"object","required":["tool","message","data","errorCode"],"additionalProperties":false,
      "properties":{"tool":{"type":"string"},"message":{"type":"string"},"data":{},"errorCode":{"type":["string","null"]}}}
      """;
  private final CommerceCatalog catalog;
  private final SafeToolExecutor safeExecutor;
  private final List<Contract> contracts;

  public CommerceToolRegistry(CommerceCatalog catalog) {
    this(catalog, new SafeToolExecutor(catalog, new ReservationPolicy()));
  }

  public CommerceToolRegistry(CommerceCatalog catalog, SafeToolExecutor safeExecutor) {
    this.catalog = catalog;
    this.safeExecutor = safeExecutor;
    contracts =
        List.of(
            contract("get_product", "Get a synthetic product by SKU", "sku", false),
            contract("get_inventory", "Get available inventory by SKU", "sku", false),
            contract(
                "get_order",
                "Get a synthetic order by orderId; tracking is not available",
                "orderId",
                false),
            contract(
                "reserve_inventory",
                "Propose a reservation. Protected proposals require separate confirmation before stock changes",
                "sku",
                true));
  }

  private static Contract contract(
      String name, String description, String field, boolean proposal) {
    String schema =
        "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\""
            + field
            + "\""
            + (proposal ? ",\"quantity\"" : "")
            + "],\"properties\":{\""
            + field
            + "\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64}"
            + (proposal
                ? ",\"quantity\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}"
                : "")
            + "}}";
    return new Contract(
        name,
        description,
        schema,
        OUTPUT,
        proposal ? "CONFIRMATION_GATED_WRITE" : "READ_ONLY",
        proposal ? "PROTECTED_CONFIRMATION_REQUIRED" : "SYNTHETIC_READ",
        proposal ? "SQLITE_TRANSACTION" : "SQLITE_LOCAL_READ",
        0);
  }

  public List<Contract> contracts() {
    return contracts;
  }

  public boolean contains(String name) {
    return contracts.stream().anyMatch(c -> c.name().equals(name));
  }

  /**
   * Executes a registered tool in baseline mode; malformed arguments and unknown tools produce
   * error results rather than being passed through to the catalog.
   */
  public ToolResult execute(String name, String arguments) {
    return execute(name, arguments, false);
  }

  /**
   * Executes a registered tool with reservation proposal mode selected.
   *
   * <p>{@code protectedMode} permits creating a protected reservation proposal, but does not
   * authorize or commit a reservation. Confirmation is a separate operation.
   */
  public ToolResult execute(String name, String arguments, boolean protectedMode) {
    if (!contains(name)) return ToolResult.error("[unregistered]", "TOOL_NOT_FOUND");
    ParsedArguments parsed = parseArguments(name, arguments);
    if (parsed.error() != null) return parsed.error();
    return name.equals("reserve_inventory")
        ? reserve(name, parsed, protectedMode)
        : read(name, parsed.value());
  }

  private ParsedArguments parseArguments(String name, String arguments) {
    ToolResult error = null;
    JsonNode args = null;
    try {
      if (arguments == null || arguments.length() > 4096)
        error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
      else args = JSON.readTree(arguments);
    } catch (JsonProcessingException invalid) {
      error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    }
    boolean proposal = name.equals("reserve_inventory");
    String field = name.equals("get_order") ? "orderId" : "sku";
    if (error != null) return new ParsedArguments(null, null, error);
    return validateArguments(name, args, field, proposal);
  }

  private static ParsedArguments validateArguments(
      String name, JsonNode args, String field, boolean proposal) {
    Set<String> allowed = proposal ? Set.of(field, "quantity") : Set.of(field);
    ToolResult error = null;
    String value = null;
    Integer quantity = null;
    if (args == null || !args.isObject() || args.size() != allowed.size()) {
      error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    } else if (!allowed.stream().allMatch(args::has)) {
      error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    } else if (!validId(args.get(field))) {
      error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    } else {
      value = args.get(field).asText();
      quantity = proposal ? parseQuantity(args.get("quantity")) : null;
      if (proposal && quantity == null) error = ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    }
    return new ParsedArguments(value, quantity, error);
  }

  private ToolResult reserve(String name, ParsedArguments parsed, boolean protectedMode) {
    return safeExecutor.proposeReservation(parsed.value(), parsed.quantity(), protectedMode);
  }

  private ToolResult read(String name, String value) {
    Object data =
        switch (name) {
          case "get_product" -> catalog.product(value);
          case "get_inventory" -> catalog.inventory(value);
          default -> catalog.order(value);
        };
    if (data == null) return ToolResult.error(name, "NOT_FOUND");
    return new ToolResult(name, message(data), data, null);
  }

  private static boolean validId(JsonNode id) {
    return id != null && id.isTextual() && id.asText().matches("[A-Za-z0-9-]{1,64}");
  }

  private static Integer parseQuantity(JsonNode quantity) {
    return quantity != null && quantity.isIntegralNumber() && quantity.canConvertToInt()
        ? quantity.asInt()
        : null;
  }

  private static String message(Object data) {
    return switch (data) {
      case CommerceCatalog.Product product ->
          product.sku()
              + ": "
              + product.name()
              + ", "
              + product.priceCents()
              + " cents "
              + product.currency()
              + ".";
      case CommerceCatalog.Inventory stock ->
          stock.sku() + " has " + stock.available() + " units available.";
      case CommerceCatalog.Order order ->
          "Order "
              + order.orderId()
              + ": "
              + order.quantity()
              + " units of "
              + order.sku()
              + "; "
              + order.status()
              + ".";
      default -> throw new IllegalStateException("Unsupported catalog result");
    };
  }

  private record ParsedArguments(String value, Integer quantity, ToolResult error) {}

  public ToolCallback[] callbacks(Consumer<ToolResult> observer) {
    return callbacks(observer, false);
  }

  /**
   * Builds model-callable callbacks and reports each resulting tool result to the observer.
   * Reservation callbacks only propose; they never perform the separate confirmation write.
   */
  public ToolCallback[] callbacks(Consumer<ToolResult> observer, boolean protectedMode) {
    return contracts.stream()
        .map(
            contract ->
                new ToolCallback() {
                  public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                        .name(contract.name())
                        .description(contract.description())
                        .inputSchema(contract.inputSchema())
                        .build();
                  }

                  public String call(String input) {
                    ToolResult result = execute(contract.name(), input, protectedMode);
                    observer.accept(result);
                    try {
                      return JSON.writeValueAsString(result);
                    } catch (JsonProcessingException impossible) {
                      throw new IllegalStateException("Cannot encode tool result", impossible);
                    }
                  }
                })
        .toArray(ToolCallback[]::new);
  }
}
