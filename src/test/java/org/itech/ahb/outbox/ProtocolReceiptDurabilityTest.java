package org.itech.ahb.outbox;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.itech.ahb.lib.astm.communication.*;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.model.*;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.serial.SerialMessageHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real SQLite, sockets and serial framing. Child-process kills prove ACK survival, not electrical hardware behavior. */
class ProtocolReceiptDurabilityTest {

  @TempDir
  Path directory;

  private static final String TEXT = "H|\\^&|||TEST\rR|1|^^^TEST|NON DÉTECTÉ\rL|1|N\r";

  private static ReceivedMessage source(String text, Transport transport) {
    return new ReceivedMessage("127.0.0.1", 43210, Protocol.ASTM, transport, null, text, null, Instant.now(), 1200);
  }

  static byte[] frame(int n, String text, boolean last) {
    byte[] body = ((char) ('0' + n) + text + (last ? '\3' : '\27')).getBytes(StandardCharsets.ISO_8859_1);
    int checksum = 0;
    for (byte b : body) checksum += (b & 255);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(2);
    out.writeBytes(body);
    out.writeBytes(String.format("%02X\r\n", checksum & 255).getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  static List<OutboxEntry> rows(SqliteOutboxStore store) {
    return store.list(new OutboxQuery(null, null, null, true, 100, 0));
  }

  private static void trigger(Path db, String sql) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var st = c.createStatement()) {
      st.execute(sql);
    }
  }

  @ParameterizedTest
  @EnumSource(value = ASTMVersion.class, names = { "LIS01_A", "E1381_95" })
  void everyFrameIsDurableBeforeAckAndRetransmissionIsIdempotent(ASTMVersion version) throws Exception {
    Path db = directory.resolve("outbox.db");
    try (
      var store = new SqliteOutboxStore(db);
      var server = new ServerSocket(0);
      var client = new Socket("127.0.0.1", server.getLocalPort());
      var peer = server.accept()
    ) {
      client.setSoTimeout(3000);
      var receiver = new GeneralASTMCommunicator(new DefaultASTMInterpreterFactory(), peer, version);
      receiver.setReceiptObserver(new DurableAstmReceipt(store, source(null, Transport.TCP)));
      var task = new FutureTask<>(() -> receiver.receiveProtocol(false));
      Thread.ofPlatform().start(task);
      var out = client.getOutputStream();
      var in = client.getInputStream();
      out.write(5);
      out.flush();
      assertThat(in.read()).isEqualTo(6);
      ByteArrayOutputStream exact = new ByteArrayOutputStream();
      for (int i = 1; i <= 9; i++) {
        byte[] bytes = frame(
          i % 8,
          i == 1 ? "H|\\^&|||TEST\r" : i == 9 ? "R|1|^^^TEST|1\rL|1|N\r" : "C|1|comment\r",
          i == 9
        );
        out.write(bytes);
        out.flush();
        assertThat(in.read()).isEqualTo(6);
        exact.writeBytes(bytes);
        // A separate database connection observes the committed bytes before EOT is sent.
        try (var reader = new SqliteOutboxStore(db)) {
          var entry = rows(reader).getFirst();
          assertThat(entry.failureReason()).isEqualTo(FailureReason.INCOMPLETE_TRANSMISSION);
          assertThat(reader.astmFrames(entry.id()).orElseThrow()).isEqualTo(exact.toByteArray());
          assertThat(entry.listenerPort()).isEqualTo(1200);
          assertThat(entry.sourceId()).isEqualTo("127.0.0.1");
          assertThatThrownBy(() -> reader.requestRetry(entry.id(), "operator", Instant.now())).isInstanceOf(
            IllegalStateException.class
          );
          assertThat(reader.claimNextDue(Instant.now(), Duration.ofSeconds(10), "test")).isEmpty();
        }
        try (
          var dbRead = DriverManager.getConnection("jdbc:sqlite:" + db);
          var st = dbRead.createStatement();
          var count = st.executeQuery("SELECT COUNT(*) FROM outbox_raw")
        ) {
          assertThat(count.next()).isTrue();
          assertThat(count.getInt(1)).isEqualTo(1);
        }
        out.write(bytes);
        out.flush();
        assertThat(in.read()).isEqualTo(6); // sender did not receive first ACK
        byte[] changed = frame(i % 8, "C|1|changed\r", i == 9);
        out.write(changed);
        out.flush();
        assertThat(in.read()).isEqualTo(21);
      }
      out.write(4);
      out.flush();
      String message = task.get(3, TimeUnit.SECONDS).getMessage();
      assertThat(message).contains("R|1|^^^TEST|1");
      assertThat(message.split("C\\|1\\|comment", -1)).hasSize(8);
      var receipt = rows(store).getFirst();
      assertThat(rows(store)).hasSize(1);
      assertThat(receipt.state()).isEqualTo(OutboxState.RECEIVED);
      assertThat(store.astmFrames(receipt.id()).orElseThrow()).isEqualTo(exact.toByteArray());
      store.markRendered(
        receipt.id(),
        List.of(
          new RenderedDelivery(
            "delivery-a",
            "sample",
            "{}",
            "connection",
            "analyzer",
            "profile",
            1,
            "http://localhost"
          ),
          new RenderedDelivery(
            "delivery-b",
            "sample2",
            "{}",
            "connection",
            "analyzer",
            "profile",
            1,
            "http://localhost"
          )
        )
      );
      assertThat(store.astmFrames("delivery-a").orElseThrow()).isEqualTo(exact.toByteArray());
      assertThat(store.astmFrames("delivery-b").orElseThrow()).isEqualTo(exact.toByteArray());
    }
  }

  @Test
  void sqliteWriteFailureNeverProducesPositiveFrameAck() throws Exception {
    Path db = directory.resolve("outbox.db");
    try (
      var store = new SqliteOutboxStore(db);
      var server = new ServerSocket(0);
      var client = new Socket("127.0.0.1", server.getLocalPort());
      var peer = server.accept()
    ) {
      client.setSoTimeout(500);
      var receiver = new GeneralASTMCommunicator(new DefaultASTMInterpreterFactory(), peer);
      receiver.setReceiptObserver(new DurableAstmReceipt(store, source(null, Transport.TCP)));
      var task = new FutureTask<>(() -> receiver.receiveProtocol(false));
      Thread.ofPlatform().start(task);
      var out = client.getOutputStream();
      var in = client.getInputStream();
      out.write(5);
      out.flush();
      assertThat(in.read()).isEqualTo(6);
      byte[] first = frame(1, "H|\\^&|||TEST\r", false);
      out.write(first);
      out.flush();
      assertThat(in.read()).isEqualTo(6);
      trigger(
        db,
        "CREATE TRIGGER fail_frame BEFORE UPDATE ON astm_wire_receipt BEGIN SELECT RAISE(ABORT,'injected write failure'); END"
      );
      out.write(frame(2, "R|1|^^^TEST|1\rL|1|N\r", true));
      out.flush();
      assertThatThrownBy(() -> task.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
      assertThatThrownBy(() -> in.read()).isInstanceOf(SocketTimeoutException.class);
      var entry = rows(store).getFirst();
      assertThat(store.astmFrames(entry.id()).orElseThrow()).isEqualTo(first);
      assertThat(store.rawBytes(entry.id()).orElseThrow()).isEqualTo(first);
    }
  }

  @Test
  void completionRollbackRetainsPartialReceiptAndRetryCannotDeliverIt() throws Exception {
    Path db = directory.resolve("outbox.db");
    try (var store = new SqliteOutboxStore(db)) {
      var receipt = new DurableAstmReceipt(store, source(null, Transport.TCP));
      byte[] bytes = frame(1, TEXT, true);
      receipt.frame(bytes);
      trigger(
        db,
        "CREATE TRIGGER fail_handoff BEFORE DELETE ON outbox WHEN OLD.failure_reason='INCOMPLETE_TRANSMISSION' BEGIN SELECT RAISE(ABORT,'injected handoff failure'); END"
      );
      assertThatThrownBy(() -> receipt.complete(TEXT)).isInstanceOf(IllegalStateException.class);
      assertThat(rows(store)).hasSize(1);
      assertThat(rows(store).getFirst().failureReason()).isEqualTo(FailureReason.INCOMPLETE_TRANSMISSION);
      trigger(db, "DROP TRIGGER fail_handoff");
      receipt.complete(TEXT);
      assertThat(rows(store)).hasSize(1);
      assertThat(rows(store).getFirst().state()).isEqualTo(OutboxState.RECEIVED);
      assertThat(store.astmFrames(rows(store).getFirst().id()).orElseThrow()).isEqualTo(bytes);
    }
  }

  @Test
  void serialReceiptsCommitBeforeAstmAndHl7Acknowledgements() throws Exception {
    Path db = directory.resolve("outbox.db");
    try (var store = new SqliteOutboxStore(db)) {
      var normalizer = new MessageNormalizer(null, null, store, null, null);
      var handler = new SerialMessageHandler(normalizer);
      var astm = handler.frameBuffer("connection:serial", "analyzer", Protocol.ASTM);
      assertThat(astm.appendData(new byte[] { 5 }).getFirst()).isEqualTo(new byte[] { 6 });
      byte[] bytes = frame(1, TEXT, true);
      assertThat(astm.appendData(bytes).getFirst()).isEqualTo(new byte[] { 6 });
      try (var independent = new SqliteOutboxStore(db)) {
        assertThat(independent.astmFrames(rows(independent).getFirst().id()).orElseThrow()).isEqualTo(bytes);
      }
      assertThat(astm.appendData(bytes).getFirst()).isEqualTo(new byte[] { 6 });
      astm.appendData(new byte[] { 4 });
      assertThat(astm.getCompletedMessages()).containsExactly(TEXT);
      var hl7 = handler.frameBuffer("connection:hl7", "analyzer", Protocol.HL7);
      String message = "MSH|^~\\&|ANALYZER|LAB|BRIDGE|LAB|20260924||ORU^R01|receipt-test|P|2.5.1\r";
      var ack = hl7.appendData(("\13" + message + "\34\r").getBytes(StandardCharsets.UTF_8));
      assertThat(new String(ack.getFirst(), StandardCharsets.UTF_8)).contains("MSA|AA|receipt-test");
      try (var independent = new SqliteOutboxStore(db)) {
        var row = rows(independent).stream().filter(e -> e.protocol() == Protocol.HL7).findFirst().orElseThrow();
        assertThat(independent.rawPayload(row.id()).orElseThrow()).isEqualTo(message);
        assertThat(row.sourceId()).isEqualTo("connection:hl7");
      }
      trigger(
        db,
        "CREATE TRIGGER fail_hl7 BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END"
      );
      assertThatThrownBy(
        () ->
          hl7.appendData(("\13" + message.replace("receipt-test", "second") + "\34\r").getBytes(StandardCharsets.UTF_8))
      ).isInstanceOf(IllegalStateException.class);
      assertThat(hl7.getCompletedMessages()).containsExactly(message);
    }
  }

  @Test
  void etbOnlySerialTransmissionIsRetainedAndNeverReturnedAsACompleteMessage() {
    try (var store = new SqliteOutboxStore(directory.resolve("outbox.db"))) {
      var buffer = new SerialMessageHandler(new MessageNormalizer(null, null, store, null, null)).frameBuffer(
        "serial",
        "a",
        Protocol.ASTM
      );
      buffer.appendData(new byte[] { 5 });
      byte[] bytes = frame(1, "H|\\^&|||TEST\r", false);
      assertThat(buffer.appendData(bytes).getFirst()).isEqualTo(new byte[] { 6 });
      buffer.appendData(new byte[] { 4 });
      assertThat(buffer.getCompletedMessages()).isEmpty();
      buffer.reset();
      assertThat(rows(store)).hasSize(1);
      assertThat(rows(store).getFirst().failureReason()).isEqualTo(FailureReason.INCOMPLETE_TRANSMISSION);
      assertThat(store.astmFrames(rows(store).getFirst().id()).orElseThrow()).isEqualTo(bytes);
    }
  }

  @Test
  void completeQueryDoesNotBecomeAFailedResult() {
    try (var store = new SqliteOutboxStore(directory.resolve("outbox.db"))) {
      var receipt = new DurableAstmReceipt(store, source(null, Transport.TCP));
      String query = "H|\\^&|||TEST\rQ|1|sample\rL|1|N\r";
      receipt.frame(frame(1, query, true));
      receipt.complete(query);
      assertThat(rows(store)).isEmpty();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
  void forcedProcessDeathRetainsAcknowledgedFramesOrCompletedReceipt(boolean complete) throws Exception {
    Path db = directory.resolve("crash.db"), portFile = directory.resolve("port"), marker = directory.resolve(
      "complete"
    );
    Process process = new ProcessBuilder(
      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
      "-cp",
      System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
      CrashReceiver.class.getName(),
      db.toString(),
      portFile.toString(),
      marker.toString()
    )
      .redirectErrorStream(true)
      .redirectOutput(directory.resolve("child.log").toFile())
      .start();
    try {
      org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> Files.exists(portFile) && !Files.readString(portFile).isBlank());
      try (var client = new Socket("127.0.0.1", Integer.parseInt(Files.readString(portFile)))) {
        client.setSoTimeout(3000);
        var out = client.getOutputStream();
        var in = client.getInputStream();
        out.write(5);
        out.flush();
        assertThat(in.read()).isEqualTo(6);
        byte[] bytes = frame(1, TEXT, complete);
        out.write(bytes);
        out.flush();
        assertThat(in.read()).isEqualTo(6);
        if (complete) {
          out.write(4);
          out.flush();
          org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(marker));
        }
        process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        try (var reopened = new SqliteOutboxStore(db)) {
          assertThat(rows(reopened)).hasSize(1);
          var row = rows(reopened).getFirst();
          assertThat(reopened.astmFrames(row.id()).orElseThrow()).isEqualTo(bytes);
          if (complete) {
            assertThat(row.state()).isEqualTo(OutboxState.RECEIVED);
            assertThat(reopened.rawPayload(row.id()).orElseThrow()).isEqualTo(TEXT);
            assertThat(row.protocolHint()).isEqualTo("TEST");
            reopened.recoverInterrupted(Instant.now());
            assertThat(reopened.claimNextDue(Instant.now(), Duration.ofSeconds(1), "restart")).isPresent();
          } else {
            assertThat(row.failureReason()).isEqualTo(FailureReason.INCOMPLETE_TRANSMISSION);
            assertThatThrownBy(() -> reopened.requestRetry(row.id(), "operator", Instant.now())).isInstanceOf(
              IllegalStateException.class
            );
          }
        }
      }
    } finally {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  public static class CrashReceiver {

    public static void main(String[] args) throws Exception {
      try (var store = new SqliteOutboxStore(Path.of(args[0])); var server = new ServerSocket(0)) {
        Files.writeString(Path.of(args[1]), Integer.toString(server.getLocalPort()));
        try (var socket = server.accept()) {
          var durable = new DurableAstmReceipt(store, source(null, Transport.TCP));
          var receiver = new GeneralASTMCommunicator(new DefaultASTMInterpreterFactory(), socket);
          receiver.setReceiptObserver(
            new AstmReceiptObserver() {
              public void frame(byte[] bytes) {
                durable.frame(bytes);
              }

              public void complete(String message) {
                durable.complete(message);
                try {
                  Files.writeString(Path.of(args[2]), "committed");
                  new CountDownLatch(1).await();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }
            }
          );
          receiver.receiveProtocol(false);
        }
      }
    }
  }
}
