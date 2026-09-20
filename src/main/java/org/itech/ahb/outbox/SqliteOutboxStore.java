package org.itech.ahb.outbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.store.SqliteSupport;

/**
 * SQLite-backed {@link OutboxStore}.
 *
 * <p>Opened with {@code synchronous=FULL} rather than WAL's usual NORMAL: for every other store in
 * the bridge a lost commit means re-reading a source that still exists, but here the database is the
 * only copy of a received result, so a commit that survives the call has to survive the machine.
 *
 * <p>Single connection, every public method {@code synchronized}. The dispatcher, the transport
 * threads that persist on receipt, and the admin API all share this instance.
 */
@Slf4j
public class SqliteOutboxStore implements OutboxStore {

  private static final String STORE_NAME = "OutboxStore";

  /**
   * Unlike the file state store, replacing this database destroys undelivered clinical results: the
   * raw messages here have no other copy once an analyzer has sent them.
   */
  private static final String CORRUPTION_CONSEQUENCE =
    "Undelivered analyzer results held in that file are NOT recoverable from the bridge. Preserve the " +
    "renamed file as incident evidence and reconcile against OpenELIS before accepting new traffic.";

  private static final String RECEIPT_PREFIX = "recv-v1";

  /** Lease owner recorded on a row the receiving thread is still rendering. */
  private static final String RECEIVE_OWNER = "receive";

  /**
   * How long the receiving thread holds a message before the dispatcher may render it instead.
   * Long enough to cover parsing and bundle building on a loaded host, short enough that a crash
   * mid-render does not strand the result for long.
   */
  private static final Duration RENDER_LEASE = Duration.ofMinutes(2);

  private static final String COLUMNS =
    "id, state, raw_hash, " +
    "(SELECT byte_length FROM outbox_raw r WHERE r.raw_hash = outbox.raw_hash) AS raw_byte_length, " +
    "LENGTH(fhir_json) AS fhir_byte_length, fhir_hash, connection_id, analyzer_id, source_id, " +
    "source_port, protocol, transport, protocol_hint, profile_id, profile_revision, accession, target_uri, " +
    "attempts, received_at, rendered_at, next_attempt_at, last_attempt_at, delivered_at, dmq_at, lease_until, " +
    "lease_owner, failure_reason, last_error, last_http_status, last_response_excerpt, oe_receipt, dismissed_at, " +
    "dismissed_by, retry_requested_by, retry_requested_at, updated_at";

  private final Path dbPath;
  private final Connection conn;
  private final boolean recoveredFromCorruption;

  public SqliteOutboxStore(Path dbPath) {
    this.dbPath = SqliteSupport.prepareDirectory(dbPath, STORE_NAME);
    SqliteSupport.OpenResult opened = SqliteSupport.openOrRecover(
      this.dbPath,
      STORE_NAME,
      SqliteSupport.Synchronous.FULL,
      CORRUPTION_CONSEQUENCE
    );
    this.conn = opened.connection();
    this.recoveredFromCorruption = opened.recoveredFromCorruption();
    int version = SqliteSupport.migrate(conn, STORE_NAME, OutboxSchema.migrations());
    log.info("Delivery outbox opened at {} (WAL, synchronous=FULL, schema v{})", this.dbPath, version);
  }

  // ---------------------------------------------------------------- receive

  @Override
  public synchronized Receipt receive(ReceivedMessage message) {
    if (message.rawText() == null || message.rawText().isEmpty()) {
      throw new IllegalArgumentException("cannot persist an empty message");
    }
    String rawHash = DeliveryIdentity.contentHash(message.rawText());
    String id = RECEIPT_PREFIX + ":" + DeliveryIdentity.contentHash(message.sourceId() + "\u0000" + rawHash);
    Instant receivedAt = message.receivedAt() == null ? Instant.now() : message.receivedAt();
    String now = ts(Instant.now());
    return inTransaction(() -> {
      try (
        PreparedStatement raw = conn.prepareStatement(
          "INSERT INTO outbox_raw (raw_hash, raw_text, raw_charset, byte_length, created_at) " +
          "VALUES (?, ?, ?, ?, ?) ON CONFLICT(raw_hash) DO NOTHING"
        )
      ) {
        raw.setString(1, rawHash);
        raw.setString(2, message.rawText());
        raw.setString(3, message.rawCharset() == null ? StandardCharsets.UTF_8.name() : message.rawCharset());
        raw.setInt(4, message.rawText().getBytes(StandardCharsets.UTF_8).length);
        raw.setString(5, now);
        raw.executeUpdate();
      }
      int inserted;
      try (
        PreparedStatement entry = conn.prepareStatement(
          "INSERT INTO outbox (id, state, raw_hash, source_id, source_port, protocol, transport, protocol_hint, " +
          "received_at, lease_until, lease_owner, updated_at) " +
          "VALUES (?, 'RECEIVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING"
        )
      ) {
        entry.setString(1, id);
        entry.setString(2, rawHash);
        entry.setString(3, message.sourceId());
        setNullableInt(entry, 4, message.sourcePort());
        entry.setString(5, message.protocol() == null ? Protocol.UNKNOWN.name() : message.protocol().name());
        entry.setString(6, message.transport() == null ? "UNKNOWN" : message.transport().name());
        entry.setString(7, message.protocolHint());
        entry.setString(8, ts(receivedAt));
        // Leased to the thread that received it. Rendering happens next, on this thread, and the
        // dispatcher must not claim the row and render it concurrently. If this process dies before
        // rendering, the lease expires and the dispatcher recovers the message instead.
        entry.setString(9, ts(Instant.now().plus(RENDER_LEASE)));
        entry.setString(10, RECEIVE_OWNER);
        entry.setString(11, now);
        inserted = entry.executeUpdate();
      }
      return new Receipt(id, rawHash, inserted == 0);
    });
  }

  // ---------------------------------------------------------------- render

  @Override
  public synchronized void markRendered(String receiptId, List<RenderedDelivery> deliveries) {
    if (deliveries == null || deliveries.isEmpty()) {
      throw new IllegalArgumentException("a rendered message must produce at least one delivery");
    }
    OutboxEntry receipt = get(receiptId).orElse(null);
    if (receipt == null) {
      // The receipt row is gone, which means these deliveries were already created: either the
      // dispatcher recovered the message after this process appeared to stall, or two copies of the
      // same message arrived at once. Either way the work is queued and there is nothing to add.
      // Only complain if the deliveries are genuinely absent.
      if (deliveries.stream().anyMatch(delivery -> get(delivery.deliveryId()).isPresent())) {
        log.debug("Outbox receipt {} was already rendered by another worker; keeping those deliveries", receiptId);
        return;
      }
      throw new IllegalStateException("no received entry " + receiptId + " to attach deliveries to");
    }
    String now = ts(Instant.now());
    inTransaction(() -> {
      for (RenderedDelivery delivery : deliveries) {
        try (
          PreparedStatement insert = conn.prepareStatement(
            "INSERT INTO outbox (id, state, raw_hash, fhir_json, fhir_hash, connection_id, analyzer_id, source_id, " +
            "source_port, protocol, transport, protocol_hint, profile_id, profile_revision, accession, target_uri, " +
            "received_at, rendered_at, updated_at) " +
            "VALUES (?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING"
          )
        ) {
          insert.setString(1, delivery.deliveryId());
          insert.setString(2, receipt.rawHash());
          insert.setString(3, delivery.fhirJson());
          insert.setString(4, DeliveryIdentity.contentHash(delivery.fhirJson()));
          insert.setString(5, delivery.connectionId());
          insert.setString(6, delivery.analyzerId());
          insert.setString(7, receipt.sourceId());
          setNullableInt(insert, 8, receipt.sourcePort());
          insert.setString(9, receipt.protocol().name());
          insert.setString(10, receipt.transport().name());
          insert.setString(11, receipt.protocolHint());
          insert.setString(12, delivery.profileId());
          setNullableInt(insert, 13, delivery.profileRevision());
          insert.setString(14, delivery.accession());
          insert.setString(15, delivery.targetUri());
          insert.setString(16, ts(receipt.receivedAt()));
          insert.setString(17, now);
          insert.setString(18, now);
          if (insert.executeUpdate() == 0) {
            // Same content, same accession, same connection: the analyzer retransmitted, or the
            // bridge restarted between rendering and deleting the receipt. Keeping the first row is
            // the point of a content-derived identity, but say so rather than absorbing it silently.
            log.warn(
              "Outbox already holds delivery {} (accession {}); treating this as a retransmission and keeping the existing entry",
              delivery.deliveryId(),
              delivery.accession()
            );
          }
        }
      }
      try (PreparedStatement del = conn.prepareStatement("DELETE FROM outbox WHERE id = ? AND state = 'RECEIVED'")) {
        del.setString(1, receiptId);
        del.executeUpdate();
      }
      return null;
    });
  }

  // ---------------------------------------------------------------- terminal transitions

  @Override
  public synchronized void markDeadLettered(String id, FailureReason reason, String error) {
    String now = ts(Instant.now());
    update("UPDATE outbox SET state = 'DMQ', failure_reason = ?, last_error = ?, dmq_at = ?, lease_until = NULL, " +
      "lease_owner = NULL, next_attempt_at = NULL, updated_at = ? WHERE id = ?", st -> {
        st.setString(1, reason.name());
        st.setString(2, truncate(error, ERROR_MAX));
        st.setString(3, now);
        st.setString(4, now);
        st.setString(5, id);
      });
  }

  @Override
  public synchronized void markRejected(
    String id,
    FailureReason reason,
    Integer httpStatus,
    String error,
    String responseExcerpt
  ) {
    String now = ts(Instant.now());
    update("UPDATE outbox SET state = 'DMQ', failure_reason = ?, last_error = ?, last_http_status = ?, " +
      "last_response_excerpt = ?, attempts = attempts + 1, last_attempt_at = ?, dmq_at = ?, lease_until = NULL, " +
      "lease_owner = NULL, next_attempt_at = NULL, updated_at = ? WHERE id = ?", st -> {
        st.setString(1, reason.name());
        st.setString(2, truncate(error, ERROR_MAX));
        setNullableInt(st, 3, httpStatus);
        st.setString(4, truncate(responseExcerpt, RESPONSE_EXCERPT_MAX));
        st.setString(5, now);
        st.setString(6, now);
        st.setString(7, now);
        st.setString(8, id);
      });
  }

  @Override
  public synchronized void markDelivered(String id, int httpStatus, String oeReceipt, String responseExcerpt) {
    String now = ts(Instant.now());
    update("UPDATE outbox SET state = 'DELIVERED', delivered_at = ?, last_http_status = ?, oe_receipt = ?, " +
      "last_response_excerpt = ?, attempts = attempts + 1, last_attempt_at = ?, lease_until = NULL, " +
      "lease_owner = NULL, next_attempt_at = NULL, failure_reason = NULL, last_error = NULL, updated_at = ? " +
      "WHERE id = ?", st -> {
        st.setString(1, now);
        st.setInt(2, httpStatus);
        st.setString(3, truncate(oeReceipt, RESPONSE_EXCERPT_MAX));
        st.setString(4, truncate(responseExcerpt, RESPONSE_EXCERPT_MAX));
        st.setString(5, now);
        st.setString(6, now);
        st.setString(7, id);
      });
  }

  @Override
  public synchronized void markRetrying(
    String id,
    Instant nextAttemptAt,
    Integer httpStatus,
    String error,
    String responseExcerpt
  ) {
    String now = ts(Instant.now());
    update("UPDATE outbox SET state = 'RETRYING', next_attempt_at = ?, last_http_status = ?, last_error = ?, " +
      "last_response_excerpt = ?, attempts = attempts + 1, last_attempt_at = ?, lease_until = NULL, " +
      "lease_owner = NULL, updated_at = ? WHERE id = ?", st -> {
        st.setString(1, ts(nextAttemptAt));
        setNullableInt(st, 2, httpStatus);
        st.setString(3, truncate(error, ERROR_MAX));
        st.setString(4, truncate(responseExcerpt, RESPONSE_EXCERPT_MAX));
        st.setString(5, now);
        st.setString(6, now);
        st.setString(7, id);
      });
  }

  // ---------------------------------------------------------------- dispatch

  @Override
  public synchronized Optional<OutboxEntry> claimNextDue(Instant now, Duration lease, String owner) {
    String nowTs = ts(now);
    return inTransaction(() -> {
      String id = null;
      try (
        PreparedStatement select = conn.prepareStatement(
          "SELECT id FROM outbox WHERE state IN ('RECEIVED','PENDING','RETRYING') " +
          "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) " +
          "AND (lease_until IS NULL OR lease_until < ?) " +
          "ORDER BY COALESCE(next_attempt_at, received_at), received_at LIMIT 1"
        )
      ) {
        select.setString(1, nowTs);
        select.setString(2, nowTs);
        try (ResultSet rs = select.executeQuery()) {
          if (rs.next()) {
            id = rs.getString(1);
          }
        }
      }
      if (id == null) {
        return Optional.empty();
      }
      try (
        PreparedStatement claim = conn.prepareStatement(
          "UPDATE outbox SET lease_until = ?, lease_owner = ?, updated_at = ? WHERE id = ?"
        )
      ) {
        claim.setString(1, ts(now.plus(lease)));
        claim.setString(2, owner);
        claim.setString(3, nowTs);
        claim.setString(4, id);
        claim.executeUpdate();
      }
      return loadWithin(id);
    });
  }

  @Override
  public synchronized void releaseLease(String id) {
    update("UPDATE outbox SET lease_until = NULL, lease_owner = NULL, updated_at = ? WHERE id = ?", st -> {
      st.setString(1, ts(Instant.now()));
      st.setString(2, id);
    });
  }

  @Override
  public synchronized int recoverInterrupted(Instant now) {
    String nowTs = ts(now);
    int[] recovered = new int[1];
    inTransaction(() -> {
      List<String> ids = new ArrayList<>();
      try (
        PreparedStatement select = conn.prepareStatement(
          "SELECT id FROM outbox WHERE state IN ('RECEIVED','PENDING','RETRYING') AND lease_until IS NOT NULL"
        );
        ResultSet rs = select.executeQuery()
      ) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
      try (
        PreparedStatement reset = conn.prepareStatement(
          "UPDATE outbox SET state = CASE WHEN state = 'RECEIVED' THEN 'RECEIVED' ELSE 'PENDING' END, " +
          "lease_until = NULL, lease_owner = NULL, next_attempt_at = NULL, updated_at = ? " +
          "WHERE state IN ('RECEIVED','PENDING','RETRYING') AND lease_until IS NOT NULL"
        )
      ) {
        reset.setString(1, nowTs);
        recovered[0] = reset.executeUpdate();
      }
      for (String id : ids) {
        insertAttempt(
          id,
          OutboxAttempt.Kind.STARTUP_RECOVERY,
          null,
          now,
          now,
          "INTERRUPTED",
          null,
          "Bridge restarted while this delivery was in flight; returned to the queue",
          null
        );
      }
      return null;
    });
    if (recovered[0] > 0) {
      log.warn(
        "Recovered {} outbox entries left in flight by a previous run; each is due now and will be redelivered",
        recovered[0]
      );
    }
    return recovered[0];
  }

  // ---------------------------------------------------------------- operator actions

  @Override
  public synchronized void recordAttempt(
    String outboxId,
    OutboxAttempt.Kind kind,
    String actor,
    Instant startedAt,
    Instant finishedAt,
    String outcome,
    Integer httpStatus,
    String error,
    String responseExcerpt
  ) {
    inTransaction(() -> {
      insertAttempt(outboxId, kind, actor, startedAt, finishedAt, outcome, httpStatus, error, responseExcerpt);
      return null;
    });
  }

  @Override
  public synchronized void requestRetry(String id, String actor, Instant now) {
    String nowTs = ts(now);
    update("UPDATE outbox SET state = 'PENDING', next_attempt_at = NULL, lease_until = NULL, lease_owner = NULL, " +
      "failure_reason = NULL, dmq_at = NULL, retry_requested_by = ?, retry_requested_at = ?, updated_at = ? " +
      "WHERE id = ? AND state != 'DELIVERED'", st -> {
        st.setString(1, actor);
        st.setString(2, nowTs);
        st.setString(3, nowTs);
        st.setString(4, id);
      });
  }

  @Override
  public synchronized void replaceRenderedPayload(
    String id,
    String fhirJson,
    String profileId,
    Integer profileRevision
  ) {
    String now = ts(Instant.now());
    update("UPDATE outbox SET fhir_json = ?, fhir_hash = ?, profile_id = ?, profile_revision = ?, rendered_at = ?, " +
      "updated_at = ? WHERE id = ?", st -> {
        st.setString(1, fhirJson);
        st.setString(2, DeliveryIdentity.contentHash(fhirJson));
        st.setString(3, profileId);
        setNullableInt(st, 4, profileRevision);
        st.setString(5, now);
        st.setString(6, now);
        st.setString(7, id);
      });
  }

  @Override
  public synchronized void dismiss(String id, String actor, Instant now) {
    String nowTs = ts(now);
    update(
      "UPDATE outbox SET dismissed_at = ?, dismissed_by = ?, updated_at = ? WHERE id = ? AND state = 'DMQ'",
      st -> {
        st.setString(1, nowTs);
        st.setString(2, actor);
        st.setString(3, nowTs);
        st.setString(4, id);
      }
    );
  }

  // ---------------------------------------------------------------- reads

  @Override
  public synchronized Optional<OutboxEntry> get(String id) {
    try (PreparedStatement st = conn.prepareStatement("SELECT " + COLUMNS + " FROM outbox WHERE id = ?")) {
      st.setString(1, id);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read outbox entry " + id, e);
    }
  }

  @Override
  public synchronized List<OutboxEntry> list(OutboxQuery query) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM outbox WHERE 1=1");
    List<Object> params = new ArrayList<>();
    if (query.state() != null) {
      sql.append(" AND state = ?");
      params.add(query.state().name());
    }
    if (query.connectionId() != null && !query.connectionId().isBlank()) {
      sql.append(" AND connection_id = ?");
      params.add(query.connectionId());
    }
    if (query.failureReason() != null) {
      sql.append(" AND failure_reason = ?");
      params.add(query.failureReason().name());
    }
    if (!query.includeDismissed()) {
      sql.append(" AND dismissed_at IS NULL");
    }
    sql.append(" ORDER BY received_at DESC, id LIMIT ? OFFSET ?");
    params.add(query.limit());
    params.add(query.offset());
    try (PreparedStatement st = conn.prepareStatement(sql.toString())) {
      for (int i = 0; i < params.size(); i++) {
        st.setObject(i + 1, params.get(i));
      }
      try (ResultSet rs = st.executeQuery()) {
        List<OutboxEntry> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(map(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to list outbox entries", e);
    }
  }

  @Override
  public synchronized List<OutboxAttempt> attempts(String outboxId) {
    try (
      PreparedStatement st = conn.prepareStatement(
        "SELECT id, outbox_id, attempt_no, kind, actor, started_at, finished_at, outcome, http_status, error, " +
        "response_excerpt FROM outbox_attempt WHERE outbox_id = ? ORDER BY attempt_no, id"
      )
    ) {
      st.setString(1, outboxId);
      try (ResultSet rs = st.executeQuery()) {
        List<OutboxAttempt> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
            new OutboxAttempt(
              rs.getLong("id"),
              rs.getString("outbox_id"),
              rs.getInt("attempt_no"),
              OutboxAttempt.Kind.valueOf(rs.getString("kind")),
              rs.getString("actor"),
              instant(rs.getString("started_at")),
              instant(rs.getString("finished_at")),
              rs.getString("outcome"),
              nullableInt(rs, "http_status"),
              rs.getString("error"),
              rs.getString("response_excerpt")
            )
          );
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read attempts for outbox entry " + outboxId, e);
    }
  }

  @Override
  public synchronized Map<OutboxState, Integer> countsByState() {
    Map<OutboxState, Integer> counts = new EnumMap<>(OutboxState.class);
    for (OutboxState state : OutboxState.values()) {
      counts.put(state, 0);
    }
    try (
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery("SELECT state, COUNT(*) FROM outbox GROUP BY state")
    ) {
      while (rs.next()) {
        counts.put(OutboxState.valueOf(rs.getString(1)), rs.getInt(2));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to count outbox entries", e);
    }
    return counts;
  }

  @Override
  public synchronized Optional<Instant> oldestUndeliveredReceivedAt() {
    try (
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(
        "SELECT MIN(received_at) FROM outbox WHERE state IN ('RECEIVED','PENDING','RETRYING')"
      )
    ) {
      if (rs.next()) {
        String value = rs.getString(1);
        return value == null ? Optional.empty() : Optional.ofNullable(instant(value));
      }
      return Optional.empty();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read the oldest undelivered outbox entry", e);
    }
  }

  @Override
  public synchronized Optional<String> rawPayload(String id) {
    try (
      PreparedStatement st = conn.prepareStatement(
        "SELECT r.raw_text FROM outbox o JOIN outbox_raw r ON r.raw_hash = o.raw_hash WHERE o.id = ?"
      )
    ) {
      st.setString(1, id);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read the received payload for " + id, e);
    }
  }

  @Override
  public synchronized Optional<String> fhirPayload(String id) {
    try (PreparedStatement st = conn.prepareStatement("SELECT fhir_json FROM outbox WHERE id = ?")) {
      st.setString(1, id);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read the rendered payload for " + id, e);
    }
  }

  // ---------------------------------------------------------------- retention

  @Override
  public synchronized int purgeExpired(Instant now, Duration deliveredRetention, Duration dismissedRetention) {
    return inTransaction(() -> {
      int removed = 0;
      try (
        PreparedStatement st = conn.prepareStatement(
          "DELETE FROM outbox WHERE state = 'DELIVERED' AND delivered_at IS NOT NULL AND delivered_at < ?"
        )
      ) {
        st.setString(1, ts(now.minus(deliveredRetention)));
        removed += st.executeUpdate();
      }
      // Only entries an operator has explicitly dismissed age out. An undismissed DMQ entry is
      // undelivered clinical data and is never purged, however old it gets.
      try (
        PreparedStatement st = conn.prepareStatement(
          "DELETE FROM outbox WHERE state = 'DMQ' AND dismissed_at IS NOT NULL AND dismissed_at < ?"
        )
      ) {
        st.setString(1, ts(now.minus(dismissedRetention)));
        removed += st.executeUpdate();
      }
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("DELETE FROM outbox_raw WHERE raw_hash NOT IN (SELECT raw_hash FROM outbox)");
      }
      return removed;
    });
  }

  @Override
  public boolean recoveredFromCorruption() {
    return recoveredFromCorruption;
  }

  @Override
  public synchronized void close() {
    try {
      if (!conn.isClosed()) {
        conn.close();
      }
    } catch (SQLException e) {
      log.warn("Failed to close the delivery outbox at {}: {}", dbPath, e.getMessage());
    }
  }

  // ---------------------------------------------------------------- internals

  private static final int ERROR_MAX = 1000;
  private static final int RESPONSE_EXCERPT_MAX = 2000;

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws SQLException;
  }

  private <T> T inTransaction(Work<T> work) {
    try {
      boolean autoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);
      try {
        T result = work.run();
        conn.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        conn.rollback();
        throw e instanceof RuntimeException runtime
          ? runtime
          : new IllegalStateException("Outbox transaction failed", e);
      } finally {
        conn.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Outbox transaction failed", e);
    }
  }

  private void update(String sql, Binder binder) {
    try (PreparedStatement st = conn.prepareStatement(sql)) {
      binder.bind(st);
      st.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to update outbox entry", e);
    }
  }

  private void insertAttempt(
    String outboxId,
    OutboxAttempt.Kind kind,
    String actor,
    Instant startedAt,
    Instant finishedAt,
    String outcome,
    Integer httpStatus,
    String error,
    String responseExcerpt
  ) throws SQLException {
    int attemptNo;
    try (
      PreparedStatement next = conn.prepareStatement(
        "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM outbox_attempt WHERE outbox_id = ?"
      )
    ) {
      next.setString(1, outboxId);
      try (ResultSet rs = next.executeQuery()) {
        attemptNo = rs.next() ? rs.getInt(1) : 1;
      }
    }
    try (
      PreparedStatement st = conn.prepareStatement(
        "INSERT INTO outbox_attempt (outbox_id, attempt_no, kind, actor, started_at, finished_at, outcome, " +
        "http_status, error, response_excerpt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      )
    ) {
      st.setString(1, outboxId);
      st.setInt(2, attemptNo);
      st.setString(3, kind.name());
      st.setString(4, actor);
      st.setString(5, ts(startedAt));
      st.setString(6, ts(finishedAt));
      st.setString(7, outcome);
      setNullableInt(st, 8, httpStatus);
      st.setString(9, truncate(error, ERROR_MAX));
      st.setString(10, truncate(responseExcerpt, RESPONSE_EXCERPT_MAX));
      st.executeUpdate();
    }
  }

  private Optional<OutboxEntry> loadWithin(String id) throws SQLException {
    try (PreparedStatement st = conn.prepareStatement("SELECT " + COLUMNS + " FROM outbox WHERE id = ?")) {
      st.setString(1, id);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private static OutboxEntry map(ResultSet rs) throws SQLException {
    return new OutboxEntry(
      rs.getString("id"),
      OutboxState.valueOf(rs.getString("state")),
      rs.getString("raw_hash"),
      rs.getInt("raw_byte_length"),
      rs.getInt("fhir_byte_length"),
      rs.getString("fhir_hash"),
      rs.getString("connection_id"),
      rs.getString("analyzer_id"),
      rs.getString("source_id"),
      nullableInt(rs, "source_port"),
      Protocol.valueOf(rs.getString("protocol")),
      Transport.valueOf(rs.getString("transport")),
      rs.getString("protocol_hint"),
      rs.getString("profile_id"),
      nullableInt(rs, "profile_revision"),
      rs.getString("accession"),
      rs.getString("target_uri"),
      rs.getInt("attempts"),
      instant(rs.getString("received_at")),
      instant(rs.getString("rendered_at")),
      instant(rs.getString("next_attempt_at")),
      instant(rs.getString("last_attempt_at")),
      instant(rs.getString("delivered_at")),
      instant(rs.getString("dmq_at")),
      instant(rs.getString("lease_until")),
      rs.getString("lease_owner"),
      enumOrNull(rs.getString("failure_reason")),
      rs.getString("last_error"),
      nullableInt(rs, "last_http_status"),
      rs.getString("last_response_excerpt"),
      rs.getString("oe_receipt"),
      instant(rs.getString("dismissed_at")),
      rs.getString("dismissed_by"),
      rs.getString("retry_requested_by"),
      instant(rs.getString("retry_requested_at")),
      instant(rs.getString("updated_at"))
    );
  }

  private static FailureReason enumOrNull(String value) {
    return value == null ? null : FailureReason.valueOf(value);
  }

  private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static void setNullableInt(PreparedStatement st, int index, Integer value) throws SQLException {
    if (value == null) {
      st.setNull(index, Types.INTEGER);
    } else {
      st.setInt(index, value);
    }
  }

  private static String ts(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static Instant instant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalStateException("Outbox holds an unparseable timestamp: " + value, e);
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
