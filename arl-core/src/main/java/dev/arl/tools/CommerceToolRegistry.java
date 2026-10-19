package dev.arl.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.arl.domain.CommerceCatalog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Central tool contracts. M2 writes require protected confirmation before stock changes. */
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
  private final List<Contract> contracts;

  public CommerceToolRegistry(CommerceCatalog catalog) {
    this.catalog = catalog;
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
                "Validate a reservation proposal only. Execution is disabled in M1; stock never changes",
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
        proposal ? "PROPOSAL_ONLY" : "READ_ONLY",
        proposal ? "WRITE_DISABLED_UNTIL_M2" : "SYNTHETIC_READ",
        "LOCAL_IN_MEMORY_NO_IO",
        0);
  }

  public List<Contract> contracts() {
    return contracts;
  }

  public boolean contains(String name) {
    return contracts.stream().anyMatch(c -> c.name().equals(name));
  }

  public ToolResult execute(String name, String arguments) {
    return execute(name, arguments, false);
  }

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
    ToolResult error = null;
    var stock = catalog.inventory(parsed.value());
    if (parsed.quantity() < 1 || parsed.quantity() > 1000) {
      error = ToolResult.error(name, "BUSINESS_VALIDATION_FAILED");
    } else if (stock == null) {
      error = ToolResult.error(name, "NOT_FOUND");
    } else if (parsed.quantity() > stock.available()) {
      error = ToolResult.error(name, "INSUFFICIENT_INVENTORY");
    }
    if (error != null) return error;
    if (protectedMode) return reservationProposal(name, parsed);
    return new ToolResult(
        name,
        "Reservation proposal only; execution and confirmation are unavailable until M2.",
        Map.of("sku", parsed.value(), "quantity", parsed.quantity()),
        "RESERVATION_NOT_ENABLED");
  }

  private ToolResult reservationProposal(String name, ParsedArguments parsed) {
    try {
      var proposal = catalog.proposeReservation(parsed.value(), parsed.quantity());
      return new ToolResult(
          name,
          "Reservation requires confirmation before stock changes.",
          Map.of(
              "confirmationId",
              proposal.confirmationId(),
              "sku",
              proposal.sku(),
              "quantity",
              proposal.quantity(),
              "expiresAt",
              proposal.expiresAt().toString()),
          null);
    } catch (CommerceCatalog.ReservationFailure failure) {
      return ToolResult.error(name, failure.getMessage());
    }
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
