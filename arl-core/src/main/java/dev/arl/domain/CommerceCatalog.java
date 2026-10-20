package dev.arl.domain;

import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
  private static final int READ_ATTEMPTS = 3;
  private static final long READ_DEADLINE_MILLIS = 2000;
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
      try (var statement = connection.createStatement()) {
        statement.execute("pragma busy_timeout=100");
      }
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
    } catch (ConditionalFailure failure) {
      rollback();
      return failure.result();
    } catch (SQLException failure) {
      rollback();
      throw new IllegalStateException("Cannot confirm reservation", failure);
    } catch (IllegalStateException failure) {
      rollback();
      throw failure;
    } finally {
      autoCommit();
    }
  }

  private ReservationCommit confirmInTransaction(String confirmationId, String idempotencyKey)
      throws SQLException {
    acquireWriteLock(confirmationId);
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
    long now = Instant.now().getEpochSecond();
    int confirmationRows =
        update(
            "update confirmations set consumed_at_epoch=? where confirmation_id=? and consumed_at_epoch is null and expires_at_epoch>=?",
            now,
            confirmation.confirmationId(),
            now);
    if (confirmationRows != 1) {
      throw new ConditionalFailure(
          failure(
              confirmationError(confirmation.confirmationId()),
              confirmation.confirmationId(),
              idempotencyKey));
    }
    int stockRows =
        update(
            "update inventory set available=available-? where sku=? and available>=?",
            confirmation.quantity(),
            confirmation.sku(),
            confirmation.quantity());
    if (stockRows != 1) {
      throw new ConditionalFailure(
          failure("INSUFFICIENT_INVENTORY", confirmation.confirmationId(), idempotencyKey));
    }
    Inventory stock = selectInventory(confirmation.sku());
    return successShell(confirmation, idempotencyKey, stock.available());
  }

  private void acquireWriteLock(String confirmationId) throws SQLException {
    update(
        "update confirmations set confirmation_id=confirmation_id where confirmation_id=?",
        confirmationId);
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
    return readWithRetry(
        "product",
        () -> {
          try (var statement =
              connection.prepareStatement(
                  "select sku, name, price_cents, currency from products where sku=?")) {
            statement.setString(1, sku);
            try (var rows = statement.executeQuery()) {
              return rows.next()
                  ? new Product(
                      rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4))
                  : null;
            }
          }
        });
  }

  private Inventory selectInventory(String sku) {
    return readWithRetry(
        "inventory",
        () -> {
          try (var statement =
              connection.prepareStatement("select sku, available from inventory where sku=?")) {
            statement.setString(1, sku);
            try (var rows = statement.executeQuery()) {
              return rows.next() ? new Inventory(rows.getString(1), rows.getInt(2)) : null;
            }
          }
        });
  }

  private Order selectOrder(String orderId) {
    return readWithRetry(
        "order",
        () -> {
          try (var statement =
              connection.prepareStatement(
                  "select order_id, sku, quantity, status from orders where order_id=?")) {
            statement.setString(1, orderId);
            try (var rows = statement.executeQuery()) {
              return rows.next()
                  ? new Order(
                      rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4))
                  : null;
            }
          }
        });
  }

  private <T> T readWithRetry(String operation, SqlRead<T> read) {
    long deadline = System.nanoTime() + READ_DEADLINE_MILLIS * 1_000_000;
    SQLException last = null;
    for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
      try {
        return read.run();
      } catch (SQLException failure) {
        last = failure;
        if (!isBusy(failure) || attempt + 1 == READ_ATTEMPTS || System.nanoTime() >= deadline)
          break;
        pauseBeforeReadRetry(attempt, deadline);
      }
    }
    throw new IllegalStateException("Cannot read " + operation, last);
  }

  private static boolean isBusy(SQLException failure) {
    int code = failure.getErrorCode();
    return code == 5 || code == 6 || "SQLITE_BUSY".equals(failure.getSQLState());
  }

  private static void pauseBeforeReadRetry(int attempt, long deadline) {
    long backoff = 20L << attempt;
    long jitter = ThreadLocalRandom.current().nextLong(1, 16);
    long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
    try {
      Thread.sleep(Math.min(backoff + jitter, remainingMillis));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("SQLite read retry interrupted", interrupted);
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
    update(sql, values);
  }

  private int update(String sql, Object... values) {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      return statement.executeUpdate();
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

  private String confirmationError(String confirmationId) {
    try {
      Confirmation confirmation = confirmation(confirmationId);
      return confirmation == null
          ? "CONFIRMATION_NOT_FOUND"
          : confirmation.expiresAtEpoch() < Instant.now().getEpochSecond()
              ? "CONFIRMATION_EXPIRED"
              : "CONFIRMATION_ALREADY_USED";
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot inspect confirmation", failure);
    }
  }

  private static final class ConditionalFailure extends RuntimeException {
    private final ReservationCommit result;

    private ConditionalFailure(ReservationCommit result) {
      this.result = result;
    }

    ReservationCommit result() {
      return result;
    }
  }

  @FunctionalInterface
  private interface SqlRead<T> {
    T run() throws SQLException;
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
