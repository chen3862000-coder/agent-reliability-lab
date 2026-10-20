package dev.arl.tools;

import dev.arl.domain.CommerceCatalog;

/** Deterministic business policy for reservation proposals and confirmation requests. */
public final class ReservationPolicy {
  public String proposalError(
      String sku, int quantity, CommerceCatalog.Inventory inventory, boolean protectedMode) {
    if (quantity < 1 || quantity > 1000) return "BUSINESS_VALIDATION_FAILED";
    if (inventory == null) return "NOT_FOUND";
    if (quantity > inventory.available()) return "INSUFFICIENT_INVENTORY";
    return protectedMode ? null : "RESERVATION_NOT_ENABLED";
  }

  public String confirmationError(String confirmationId, String idempotencyKey) {
    if (confirmationId == null || !confirmationId.matches("[A-Za-z0-9-]{1,128}"))
      return "INVALID_CONFIRMATION";
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9-]{8,128}"))
      return "INVALID_IDEMPOTENCY_KEY";
    return null;
  }
}
