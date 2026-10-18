package dev.arl.domain;

import java.util.Map;

/** Immutable synthetic fixtures. A fresh instance resets the data; no write API exists in M1. */
public final class CommerceCatalog {
  public record Product(String sku, String name, int priceCents, String currency) {}

  public record Inventory(String sku, int available) {}

  public record Order(String orderId, String sku, int quantity, String status) {}

  private final Map<String, Product> products =
      Map.of(
          "SKU-1001", new Product("SKU-1001", "Mechanical Keyboard", 7900, "USD"),
          "SKU-1002", new Product("SKU-1002", "Wireless Mouse", 2900, "USD"));
  private final Map<String, Inventory> inventory =
      Map.of("SKU-1001", new Inventory("SKU-1001", 20), "SKU-1002", new Inventory("SKU-1002", 0));
  private final Map<String, Order> orders =
      Map.of("123", new Order("123", "SKU-1001", 5, "PROCESSING"));

  public Product product(String sku) {
    return products.get(sku);
  }

  public Inventory inventory(String sku) {
    return inventory.get(sku);
  }

  public Order order(String orderId) {
    return orders.get(orderId);
  }
}
