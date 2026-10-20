package dev.arl.tools;

import dev.arl.domain.CommerceCatalog;
import java.util.Map;

/** Trusted execution boundary for reservation proposals and confirmed writes. */
public final class SafeToolExecutor {
  private final CommerceCatalog catalog;
  private final ReservationPolicy policy;

  public SafeToolExecutor(CommerceCatalog catalog, ReservationPolicy policy) {
    this.catalog = catalog;
    this.policy = policy;
  }

  public ToolResult proposeReservation(String sku, int quantity, boolean protectedMode) {
    var stock = catalog.inventory(sku);
    String error = policy.proposalError(sku, quantity, stock, protectedMode);
    if ("RESERVATION_NOT_ENABLED".equals(error)) {
      return new ToolResult(
          "reserve_inventory",
          "Reservation proposal only in BASELINE mode; stock is unchanged.",
          Map.of("sku", sku, "quantity", quantity),
          error);
    }
    if (error != null) return ToolResult.error("reserve_inventory", error);
    return proposal(catalog.proposeReservation(sku, quantity));
  }

  public CommerceCatalog.ReservationCommit confirmReservation(
      String confirmationId, String idempotencyKey) {
    String error = policy.confirmationError(confirmationId, idempotencyKey);
    if (error != null)
      return new CommerceCatalog.ReservationCommit(
          "ERROR", error.replace('_', ' '), error, confirmationId, idempotencyKey, null, 0, null);
    return catalog.confirmReservation(confirmationId, idempotencyKey);
  }

  private static ToolResult proposal(CommerceCatalog.ReservationProposal proposal) {
    return new ToolResult(
        "reserve_inventory",
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
  }
}
