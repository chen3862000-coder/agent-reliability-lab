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

/** Central M1 tool contracts. All operations are local, read-only and non-retrying. */
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
    if (!contains(name)) return ToolResult.error("[unregistered]", "TOOL_NOT_FOUND");
    JsonNode args;
    try {
      if (arguments == null || arguments.length() > 4096)
        return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
      args = JSON.readTree(arguments);
    } catch (JsonProcessingException invalid) {
      return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    }
    boolean proposal = name.equals("reserve_inventory");
    String field = name.equals("get_order") ? "orderId" : "sku";
    Set<String> allowed = proposal ? Set.of(field, "quantity") : Set.of(field);
    if (args == null || !args.isObject() || args.size() != allowed.size())
      return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    for (String key : allowed)
      if (!args.has(key)) return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    JsonNode id = args.get(field);
    if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9-]{1,64}"))
      return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
    String value = id.asText();
    if (proposal) {
      JsonNode quantity = args.get("quantity");
      if (!quantity.isIntegralNumber() || !quantity.canConvertToInt())
        return ToolResult.error(name, "INVALID_TOOL_ARGUMENTS");
      if (quantity.asInt() < 1 || quantity.asInt() > 1000)
        return ToolResult.error(name, "BUSINESS_VALIDATION_FAILED");
      var stock = catalog.inventory(value);
      if (stock == null) return ToolResult.error(name, "NOT_FOUND");
      if (quantity.asInt() > stock.available())
        return ToolResult.error(name, "INSUFFICIENT_INVENTORY");
      return new ToolResult(
          name,
          "Reservation proposal only; execution and confirmation are unavailable until M2.",
          Map.of("sku", value, "quantity", quantity.asInt()),
          "RESERVATION_NOT_ENABLED");
    }
    Object data =
        switch (name) {
          case "get_product" -> catalog.product(value);
          case "get_inventory" -> catalog.inventory(value);
          default -> catalog.order(value);
        };
    if (data == null) return ToolResult.error(name, "NOT_FOUND");
    String message =
        switch (data) {
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
    return new ToolResult(name, message, data, null);
  }

  public ToolCallback[] callbacks(Consumer<ToolResult> observer) {
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
                    ToolResult result = execute(contract.name(), input);
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
