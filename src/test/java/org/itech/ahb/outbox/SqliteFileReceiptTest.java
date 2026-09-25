package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.itech.ahb.store.SqliteSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite receipt/upgrade boundaries; parser and HTTP recovery are covered by FILE integration tests. */
class SqliteFileReceiptTest {

  @TempDir
  Path directory;

  private static final byte[] BYTES = { 0, 1, 2, (byte) 255, (byte) 128, 13, 10 };
  private static final String CONTEXT = "{\"schemaVersion\":1,\"selectedAssay\":\"B\"}";

  private ReceivedFile file(String path, String interpretation) {
    return new ReceivedFile(BYTES, path, "connection-1", "oe-1", "profile-1", 3, CONTEXT, interpretation);
  }

  @Test
  void binaryBytesAndContextSurviveRestartFanoutAndRenameWithoutDuplicateDelivery() {
    Path db = directory.resolve("outbox.db");
    String receiptId;
    try (var store = new SqliteOutboxStore(db)) {
      var receipt = store.receiveFile(file("/input/original.xlsx", "assay-B"));
      receiptId = receipt.id();
      assertEquals(DeliveryIdentity.contentHash(BYTES), receipt.rawHash());
      assertEquals(BYTES.length, store.get(receiptId).orElseThrow().rawByteLength());
      assertArrayEquals(BYTES, store.rawBytes(receiptId).orElseThrow());
      assertEquals("BASE64", store.rawEncoding(receiptId).orElseThrow());
      assertEquals(CONTEXT, store.fileContext(receiptId).orElseThrow());
      assertFalse(receipt.alreadyPresent());
    }
    try (var store = new SqliteOutboxStore(db)) {
      assertArrayEquals(BYTES, store.rawBytes(receiptId).orElseThrow());
      store.markRendered(receiptId, List.of(delivery("one"), delivery("two")));
      store.markDelivered("one", 200, "oe-receipt", "ok");
      assertTrue(store.get(receiptId).isEmpty());
    }
    try (var store = new SqliteOutboxStore(db)) {
      for (String id : List.of("one", "two")) {
        assertArrayEquals(BYTES, store.rawBytes(id).orElseThrow());
        assertEquals(CONTEXT, store.fileContext(id).orElseThrow());
      }
      assertTrue(store.receiveFile(file("/input/renamed.xlsx", "assay-B")).alreadyPresent());
      assertEquals(1, store.countsByState().get(OutboxState.PENDING));
      assertEquals(1, store.countsByState().get(OutboxState.DELIVERED));
      assertThrows(IllegalArgumentException.class, () -> store.receiveFile(file("/input/renamed.xlsx", "assay-A")));
      store.purgeExpired(Instant.now().plus(Duration.ofDays(30)), Duration.ofDays(7), Duration.ofDays(7));
      assertTrue(store.get("one").isEmpty());
      assertArrayEquals(BYTES, store.rawBytes("two").orElseThrow(), "undelivered bytes must survive retention cleanup");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = { true, false })
  void sharedFileAndTextBytesKeepTheirExactReplayRepresentationInEitherInsertionOrder(boolean fileFirst) {
    Path db = directory.resolve("mixed.db");
    String text = "Sample,Result\nPatient,élevé\n";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ReceivedFile file = new ReceivedFile(bytes, "/file.csv", "conn", "oe", "profile", 1, CONTEXT, "parse");
    ReceivedMessage message = new ReceivedMessage(
      "host",
      1,
      org.itech.ahb.model.Protocol.CSV,
      org.itech.ahb.model.Transport.HTTP,
      null,
      text,
      "UTF-8",
      Instant.now()
    );
    Receipt fileReceipt;
    Receipt textReceipt;
    try (var store = new SqliteOutboxStore(db)) {
      if (fileFirst) {
        fileReceipt = store.receiveFile(file);
        textReceipt = store.receive(message);
      } else {
        textReceipt = store.receive(message);
        fileReceipt = store.receiveFile(file);
      }
      assertEquals(fileReceipt.rawHash(), textReceipt.rawHash());
    }
    try (var store = new SqliteOutboxStore(db)) {
      assertEquals(
        text,
        store.rawPayload(textReceipt.id()).orElseThrow(),
        "text renderer must receive text, not Base64"
      );
      assertArrayEquals(bytes, store.rawBytes(fileReceipt.id()).orElseThrow());
      assertEquals(CONTEXT, store.fileContext(fileReceipt.id()).orElseThrow());
      assertTrue(store.fileContext(textReceipt.id()).isEmpty(), "interpretation belongs to receipt, not shared bytes");
    }
  }

  @Test
  void identicalBytesOnDifferentConnectionsKeepIndependentContexts() {
    Path db = directory.resolve("connections.db");
    String first;
    String second;
    try (var store = new SqliteOutboxStore(db)) {
      first = store.receiveFile(file("/one.xlsx", "one")).id();
      second = store
        .receiveFile(
          new ReceivedFile(
            BYTES,
            "/two.xlsx",
            "connection-2",
            "oe-2",
            "profile-2",
            5,
            "{\"selectedAssay\":\"C\"}",
            "two"
          )
        )
        .id();
      assertNotEquals(first, second);
      store.markRendered(first, List.of(delivery("one")));
      store.markRendered(
        second,
        List.of(new RenderedDelivery("two", "two", "{}", "connection-2", "oe-2", "profile-2", 5, "http://oe/fhir"))
      );
    }
    try (var store = new SqliteOutboxStore(db)) {
      assertEquals(CONTEXT, store.fileContext("one").orElseThrow());
      assertEquals("{\"selectedAssay\":\"C\"}", store.fileContext("two").orElseThrow());
      assertArrayEquals(BYTES, store.rawBytes("one").orElseThrow());
      assertArrayEquals(BYTES, store.rawBytes("two").orElseThrow());
      assertEquals("connection-2", store.get("two").orElseThrow().connectionId());
      assertEquals(5, store.get("two").orElseThrow().profileRevision());
    }
  }

  @Test
  void cannotAcknowledgeBytesWithoutTheirContext() throws Exception {
    Path db = directory.resolve("failed.db");
    try (var store = new SqliteOutboxStore(db)) {
      try (
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()
      ) {
        statement.execute(
          "CREATE TRIGGER reject_file BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'storage failure'); END"
        );
      }
      assertThrows(IllegalStateException.class, () -> store.receiveFile(file("/file.xlsx", "assay-B")));
      assertTrue(store.countsByState().values().stream().allMatch(count -> count == 0));
      try (
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM outbox_raw")
      ) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "bytes and receipt context must roll back together");
      }
    }
  }

  @Test
  void upgradesExistingTextOutboxWithoutChangingBytesIdentityOrForeignKeys() throws Exception {
    Path db = directory.resolve("old.db");
    String raw = "H|\\^&|original\r";
    String hash = DeliveryIdentity.contentHash(raw);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      SqliteSupport.migrate(connection, "old-outbox", OutboxSchema.migrations().subList(0, 2));
      try (var insert = connection.prepareStatement("INSERT INTO outbox_raw VALUES (?, ?, 'UTF-8', ?, ?)")) {
        insert.setString(1, hash);
        insert.setString(2, raw);
        insert.setInt(3, raw.getBytes(StandardCharsets.UTF_8).length);
        insert.setString(4, Instant.now().toString());
        insert.executeUpdate();
      }
      try (
        var insert = connection.prepareStatement(
          "INSERT INTO outbox (id,state,raw_hash,source_id,protocol,transport,received_at,updated_at) VALUES ('old','DMQ',?,'source','ASTM','TCP',?,?)"
        )
      ) {
        insert.setString(1, hash);
        insert.setString(2, Instant.now().toString());
        insert.setString(3, Instant.now().toString());
        insert.executeUpdate();
      }
    }
    try (var store = new SqliteOutboxStore(db)) {
      assertEquals(raw, store.rawPayload("old").orElseThrow());
      assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8), store.rawBytes("old").orElseThrow());
      assertEquals(hash, store.get("old").orElseThrow().rawHash());
      assertEquals(OutboxState.DMQ, store.get("old").orElseThrow().state());
      assertEquals("UTF8", store.rawEncoding("old").orElseThrow());
      assertTrue(store.fileContext("old").isEmpty());
      store.receiveFile(file("/new.xlsx", "assay-B"));
      try (
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var violations = statement.executeQuery("PRAGMA foreign_key_check")
      ) {
        assertFalse(violations.next());
      }
    }
  }

  private RenderedDelivery delivery(String id) {
    return new RenderedDelivery(
      id,
      id,
      "{\"id\":\"" + id + "\"}",
      "connection-1",
      "oe-1",
      "profile-1",
      3,
      "http://oe/fhir"
    );
  }
}
