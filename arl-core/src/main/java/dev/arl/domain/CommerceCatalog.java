package dev.arl.domain;

import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** SQLite-backed synthetic commerce state and M2 confirmation/idempotency boundary. */
public final class CommerceCatalog implements AutoCloseable {
  public record Product(String sku, String name, int priceCents, String currency) {}

  public record Inventory(String sku, int available) {}

  public record Order(String orderId, String sku, int quantity, String status) {}

  public record ReservationProposal(
      String confirmationId, String sku, int quantity, Instant expiresAt) {}

  public record ReservationCommit(
      String status,
      String message,
      String errorCode,
      String confirmationId,
      String idempotencyKey,
      String sku,
      int quantity,
      Integer remaining) {}

  private static final int CONFIRMATION_TTL_SECONDS = 300;
  private final Connection connection;

  public CommerceCatalog() {
    this("jdbc:sqlite:file:arl-" + UUID.randomUUID() + "?mode=memory&cache=shared");
  }

  public static CommerceCatalog fileBacked(String path) {
    return new CommerceCatalog("jdbc:sqlite:" + path);
  }

  public CommerceCatalog(String url) {
    try {
      connection = DriverManager.getConnection(url);
      connection.setAutoCommit(true);
      initialize();
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot initialize commerce catalog", failure);
    }
  }

  public synchronized Product product(String sku) {
    return selectProduct(sku);
  }

  public synchronized Inventory inventory(String sku) {
    return selectInventory(sku);
  }

  public synchronized Order order(String orderId) {
    return selectOrder(orderId);
  }

  public synchronized ReservationProposal proposeReservation(String sku, int quantity) {
    Inventory stock = selectInventory(sku);
    if (stock == null) throw new ReservationFailure("NOT_FOUND");
    if (quantity > stock.available()) throw new ReservationFailure("INSUFFICIENT_INVENTORY");
    String confirmationId = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plusSeconds(CONFIRMATION_TTL_SECONDS);
    execute(
        "insert into confirmations(confirmation_id, operation, sku, quantity, fingerprint, expires_at_epoch) values(?,?,?,?,?,?)",
        confirmationId,
        "reserve_inventory",
        sku,
        quantity,
        fingerprint(sku, quantity),
        expiresAt.getEpochSecond());
    return new ReservationProposal(confirmationId, sku, quantity, expiresAt);
  }

  public synchronized ReservationCommit confirmReservation(
      String confirmationId, String idempotencyKey) {
    try {
      connection.setAutoCommit(false);
      ReservationCommit result = confirmInTransaction(confirmationId, idempotencyKey);
      connection.commit();
      return result;
    } catch (SQLException failure) {
      rollback();
      throw new IllegalStateException("Cannot confirm reservation", failure);
    } finally {
      autoCommit();
    }
  }

  private ReservationCommit confirmInTransaction(String confirmationId, String idempotencyKey)
      throws SQLException {
    var confirmation = confirmation(confirmationId);
    var previous = idempotency(idempotencyKey);
    if (previous != null) return previousFor(previous, confirmationId, idempotencyKey);
    if (confirmation == null)
      return failure("CONFIRMATION_NOT_FOUND", confirmationId, idempotencyKey);
    ReservationCommit result = validateConfirmation(confirmation, idempotencyKey);
    if (result.errorCode() == null) result = consume(confirmation, idempotencyKey);
    insertIdempotency(idempotencyKey, confirmation, result);
    return result;
  }

  private ReservationCommit validateConfirmation(Confirmation confirmation, String idempotencyKey) {
    ReservationCommit result = null;
    if (confirmation.consumed()) {
      result = failure("CONFIRMATION_ALREADY_USED", confirmation.confirmationId(), idempotencyKey);
    } else if (confirmation.expiresAtEpoch() < Instant.now().getEpochSecond()) {
      result = failure("CONFIRMATION_EXPIRED", confirmation.confirmationId(), idempotencyKey);
    } else if (selectInventory(confirmation.sku()).available() < confirmation.quantity()) {
      result = failure("INSUFFICIENT_INVENTORY", confirmation.confirmationId(), idempotencyKey);
    }
    return result == null ? successShell(confirmation, idempotencyKey, null) : result;
  }

  private ReservationCommit consume(Confirmation confirmation, String idempotencyKey)
      throws SQLException {
    Inventory stock = selectInventory(confirmation.sku());
    int remaining = stock.available() - confirmation.quantity();
    execute(
        "update inventory set available=? where sku=? and available>=?",
        remaining,
        confirmation.sku(),
        confirmation.quantity());
    execute(
        "update confirmations set consumed_at_epoch=? where confirmation_id=?",
        Instant.now().getEpochSecond(),
        confirmation.confirmationId());
    return successShell(confirmation, idempotencyKey, remaining);
  }

  private ReservationCommit previousFor(
      Idempotency previous, String confirmationId, String idempotencyKey) {
    if (!previous.confirmationId().equals(confirmationId)) {
      return failure("IDEMPOTENCY_KEY_REUSED", confirmationId, idempotencyKey);
    }
    return new ReservationCommit(
        previous.errorCode() == null ? "SUCCESS" : "ERROR",
        previous.message(),
        previous.errorCode(),
        previous.confirmationId(),
        idempotencyKey,
        previous.sku(),
        previous.quantity(),
        previous.remaining());
  }

  private void insertIdempotency(
      String idempotencyKey, Confirmation confirmation, ReservationCommit result)
      throws SQLException {
    execute(
        "insert into idempotency(idempotency_key, confirmation_id, fingerprint, result_code, message, sku, quantity, remaining) values(?,?,?,?,?,?,?,?)",
        idempotencyKey,
        confirmation.confirmationId(),
        confirmation.fingerprint(),
        result.errorCode(),
        result.message(),
        confirmation.sku(),
        confirmation.quantity(),
        result.remaining());
  }

  private ReservationCommit successShell(
      Confirmation confirmation, String idempotencyKey, Integer remaining) {
    return new ReservationCommit(
        "SUCCESS",
        "Reserved " + confirmation.quantity() + " units of " + confirmation.sku() + ".",
        null,
        confirmation.confirmationId(),
        idempotencyKey,
        confirmation.sku(),
        confirmation.quantity(),
        remaining);
  }

  private ReservationCommit failure(String code, String confirmationId, String idempotencyKey) {
    return new ReservationCommit(
        "ERROR", code.replace('_', ' '), code, confirmationId, idempotencyKey, null, 0, null);
  }

  private Product selectProduct(String sku) {
    try (var statement =
        connection.prepareStatement(
            "select sku, name, price_cents, currency from products where sku=?")) {
      statement.setString(1, sku);
      try (var rows = statement.executeQuery()) {
        return rows.next()
            ? new Product(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4))
            : null;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot read product", failure);
    }
  }

  private Inventory selectInventory(String sku) {
    try (var statement =
        connection.prepareStatement("select sku, available from inventory where sku=?")) {
      statement.setString(1, sku);
      try (var rows = statement.executeQuery()) {
        return rows.next() ? new Inventory(rows.getString(1), rows.getInt(2)) : null;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot read inventory", failure);
    }
  }

  private Order selectOrder(String orderId) {
    try (var statement =
        connection.prepareStatement(
            "select order_id, sku, quantity, status from orders where order_id=?")) {
      statement.setString(1, orderId);
      try (var rows = statement.executeQuery()) {
        return rows.next()
            ? new Order(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4))
            : null;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot read order", failure);
    }
  }

  private Confirmation confirmation(String confirmationId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "select confirmation_id, sku, quantity, fingerprint, expires_at_epoch, consumed_at_epoch from confirmations where confirmation_id=?")) {
      statement.setString(1, confirmationId);
      try (var rows = statement.executeQuery()) {
        return rows.next()
            ? new Confirmation(
                rows.getString(1),
                rows.getString(2),
                rows.getInt(3),
                rows.getString(4),
                rows.getLong(5),
                rows.getObject(6) != null)
            : null;
      }
    }
  }

  private Idempotency idempotency(String key) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "select confirmation_id, result_code, message, sku, quantity, remaining from idempotency where idempotency_key=?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        return rows.next()
            ? new Idempotency(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getInt(5),
                (Integer) rows.getObject(6))
            : null;
      }
    }
  }

  private void initialize() throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("pragma foreign_keys=on");
      statement.execute(
          "create table if not exists products(sku text primary key, name text not null, price_cents integer not null, currency text not null)");
      statement.execute(
          "create table if not exists inventory(sku text primary key references products(sku), available integer not null check(available >= 0))");
      statement.execute(
          "create table if not exists orders(order_id text primary key, sku text not null, quantity integer not null, status text not null)");
      statement.execute(
          "create table if not exists confirmations(confirmation_id text primary key, operation text not null, sku text not null, quantity integer not null, fingerprint text not null, expires_at_epoch integer not null, consumed_at_epoch integer)");
      statement.execute(
          "create table if not exists idempotency(idempotency_key text primary key, confirmation_id text not null, fingerprint text not null, result_code text, message text not null, sku text, quantity integer, remaining integer)");
    }
    seed();
  }

  private void seed() {
    execute(
        "insert or ignore into products values(?,?,?,?)",
        "SKU-1001",
        "Mechanical Keyboard",
        7900,
        "USD");
    execute(
        "insert or ignore into products values(?,?,?,?)",
        "SKU-1002",
        "Wireless Mouse",
        2900,
        "USD");
    execute("insert or ignore into inventory values(?,?)", "SKU-1001", 20);
    execute("insert or ignore into inventory values(?,?)", "SKU-1002", 0);
    execute("insert or ignore into orders values(?,?,?,?)", "123", "SKU-1001", 5, "PROCESSING");
  }

  private void execute(String sql, Object... values) {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot execute catalog statement", failure);
    }
  }

  private static String fingerprint(String sku, int quantity) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(("reserve_inventory|" + sku + "|" + quantity).getBytes());
      return HexFormat.of().formatHex(hash);
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private void rollback() {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // Preserve the original failure.
    }
  }

  private void autoCommit() {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot restore autocommit", failure);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot close commerce catalog", failure);
    }
  }

  public static final class ReservationFailure extends RuntimeException {
    public ReservationFailure(String code) {
      super(code);
    }
  }

  private record Confirmation(
      String confirmationId,
      String sku,
      int quantity,
      String fingerprint,
      long expiresAtEpoch,
      boolean consumed) {}

  private record Idempotency(
      String confirmationId,
      String errorCode,
      String message,
      String sku,
      int quantity,
      Integer remaining) {}
}
