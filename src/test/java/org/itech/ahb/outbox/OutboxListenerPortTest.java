package org.itech.ahb.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.store.SqliteSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outbox keeps the shared listener a message arrived on, so a message dead-lettered because its
 * connection could not be resolved resolves the same way once the configuration is corrected.
 */
class OutboxListenerPortTest {

  private static final String RAW_ASTM = "H|\\^&|||GENEXPERT^GeneXpert^4.6.0\rL|1|N\r";

  @TempDir
  Path directory;

  @Test
  void receiptAndItsRenderedDeliveriesKeepTheListenerPort() {
    try (SqliteOutboxStore store = new SqliteOutboxStore(directory.resolve("outbox.db"))) {
      Receipt receipt = store.receive(message(12001));

      assertThat(store.get(receipt.id()).orElseThrow().listenerPort()).isEqualTo(12001);

      store.markRendered(receipt.id(), List.of(delivery()));
      assertThat(store.get("astm-v1:shared").orElseThrow().listenerPort()).isEqualTo(12001);
    }
  }

  @Test
  void messagesWithoutASharedListenerStoreNoPort() {
    try (SqliteOutboxStore store = new SqliteOutboxStore(directory.resolve("outbox.db"))) {
      Receipt receipt = store.receive(
        new ReceivedMessage("/dev/ttyUSB0", null, Protocol.ASTM, Transport.SERIAL, null, RAW_ASTM, null, Instant.now())
      );

      assertThat(store.get(receipt.id()).orElseThrow().listenerPort()).isNull();
    }
  }

  @Test
  void anOutboxWrittenByBridge311IsUpgradedInPlaceAndKeepsItsRows() throws Exception {
    Path dbPath = directory.resolve("outbox.db");
    // Build the store exactly as 3.1.1 left it: schema version 1 with a held row.
    try (Connection connection = SqliteSupport.openWithPragmas(dbPath, SqliteSupport.Synchronous.FULL)) {
      SqliteSupport.migrate(connection, "outbox", OutboxSchema.migrations().subList(0, 1));
      try (Statement st = connection.createStatement()) {
        st.execute(
          "INSERT INTO outbox_raw (raw_hash, raw_text, raw_charset, byte_length, created_at) " +
          "VALUES ('h1', 'H|x', 'UTF-8', 3, '2026-09-22T00:00:00Z')"
        );
      }
      try (
        PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO outbox (id, state, raw_hash, source_id, protocol, transport, received_at, " +
          "failure_reason, updated_at) VALUES (?, 'DMQ', 'h1', ?, 'ASTM', 'TCP', ?, 'UNREGISTERED_SOURCE', ?)"
        )
      ) {
        insert.setString(1, "held-by-311");
        insert.setString(2, "connection:gx-legacy");
        insert.setString(3, "2026-09-22T00:00:00Z");
        insert.setString(4, "2026-09-22T00:00:00Z");
        insert.executeUpdate();
      }
      assertThat(SqliteSupport.userVersion(connection)).isEqualTo(1);
    }

    try (SqliteOutboxStore store = new SqliteOutboxStore(dbPath)) {
      OutboxEntry held = store.get("held-by-311").orElseThrow();
      assertThat(held.sourceId()).isEqualTo("connection:gx-legacy");
      assertThat(held.listenerPort()).isNull();
      assertThat(held.failureReason()).isEqualTo(FailureReason.UNREGISTERED_SOURCE);

      Receipt receipt = store.receive(message(12001));
      assertThat(store.get(receipt.id()).orElseThrow().listenerPort()).isEqualTo(12001);
    }
  }

  private static ReceivedMessage message(int listenerPort) {
    return new ReceivedMessage(
      "10.0.0.21",
      null,
      Protocol.ASTM,
      Transport.TCP,
      "GENEXPERT^GeneXpert^4.6.0",
      RAW_ASTM,
      null,
      Instant.now(),
      listenerPort
    );
  }

  private static RenderedDelivery delivery() {
    return new RenderedDelivery(
      "astm-v1:shared",
      "ACC-1",
      "{\"resourceType\":\"Bundle\"}",
      "gx-a",
      "oe-gx-a",
      "genexpert-astm",
      1,
      "https://oe:8443/api/OpenELIS-Global/analyzer/fhir"
    );
  }
}
