package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outbox exists to keep one promise: a received result is still here, complete, until OpenELIS
 * accepts it or an operator can see why it could not be delivered. These tests are written against
 * that promise rather than against the SQL.
 */
class SqliteOutboxStoreTest {

  private static final String RAW_ASTM =
    "H|\\^&|||GeneXpert^1.0|||||||P|1|20260919\rP|1\rO|1|ACC-1||^^^MTB\rR|1|^^^MTB|NEG||||F\rL|1|N\r";

  @TempDir
  Path dir;

  private Path dbPath;
  private SqliteOutboxStore store;

  @BeforeEach
  void openStore() {
    dbPath = dir.resolve("outbox.db");
    store = new SqliteOutboxStore(dbPath);
  }

  @AfterEach
  void closeStore() {
    if (store != null) {
      store.close();
    }
  }

  private ReceivedMessage astm(String raw) {
    return new ReceivedMessage(
      "192.168.1.10",
      12001,
      Protocol.ASTM,
      Transport.TCP,
      "GeneXpert",
      raw,
      "ISO-8859-1",
      Instant.now()
    );
  }

  private RenderedDelivery delivery(String id, String accession) {
    return new RenderedDelivery(
      id,
      accession,
      "{\"resourceType\":\"Bundle\",\"identifier\":{\"value\":\"" + id + "\"}}",
      "bridge-connection-7f3c",
      "oe-analyzer-42",
      "genexpert-astm",
      1,
      "https://oe:8443/api/OpenELIS-Global/analyzer/fhir"
    );
  }

  private String receiveAndRender(String raw, String deliveryId, String accession) {
    Receipt receipt = store.receive(astm(raw));
    store.markRendered(receipt.id(), List.of(delivery(deliveryId, accession)));
    return deliveryId;
  }

  @Nested
  @DisplayName("receipt")
  class Receipt_ {

    @Test
    @DisplayName("persists the complete received message before anything is parsed")
    void persistsCompleteMessage() {
      Receipt receipt = store.receive(astm(RAW_ASTM));

      OutboxEntry entry = store.get(receipt.id()).orElseThrow();
      assertEquals(OutboxState.RECEIVED, entry.state());
      assertEquals(
        RAW_ASTM,
        store.rawPayload(receipt.id()).orElseThrow(),
        "the raw message must be stored whole, not snippeted"
      );
      assertEquals(RAW_ASTM.length(), entry.rawByteLength());
      assertEquals("192.168.1.10", entry.sourceId());
      assertEquals(Protocol.ASTM, entry.protocol());
      assertFalse(receipt.alreadyPresent());
    }

    @Test
    @DisplayName("treats identical content from the same source as the same receipt")
    void deduplicatesRetransmission() {
      Receipt first = store.receive(astm(RAW_ASTM));
      Receipt second = store.receive(astm(RAW_ASTM));

      assertEquals(first.id(), second.id());
      assertTrue(second.alreadyPresent(), "a retransmission must not create a second entry");
      assertEquals(1, store.countsByState().get(OutboxState.RECEIVED));
    }

    @Test
    @DisplayName("stores one copy of content shared by several deliveries")
    void storesContentOnce() {
      Receipt receipt = store.receive(astm(RAW_ASTM));
      store.markRendered(receipt.id(), List.of(delivery("astm-v1:a", "ACC-1"), delivery("astm-v1:b", "ACC-2")));

      assertEquals(RAW_ASTM, store.rawPayload("astm-v1:a").orElseThrow());
      assertEquals(RAW_ASTM, store.rawPayload("astm-v1:b").orElseThrow());
    }

    @Test
    @DisplayName("refuses an empty message rather than recording a hole")
    void refusesEmptyMessage() {
      assertThrows(IllegalArgumentException.class, () -> store.receive(astm("")));
    }
  }

  @Nested
  @DisplayName("rendering")
  class Rendering {

    @Test
    @DisplayName("replaces the received row with one pending delivery per accession")
    void replacesReceiptWithDeliveries() {
      Receipt receipt = store.receive(astm(RAW_ASTM));
      store.markRendered(receipt.id(), List.of(delivery("astm-v1:a", "ACC-1"), delivery("astm-v1:b", "ACC-2")));

      assertTrue(store.get(receipt.id()).isEmpty(), "the receipt is superseded by its deliveries");
      assertEquals(2, store.countsByState().get(OutboxState.PENDING));
      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals("ACC-1", entry.accession());
      assertEquals("bridge-connection-7f3c", entry.connectionId());
      assertEquals(1, entry.profileRevision(), "the profile pin travels with the delivery for replay");
      assertNotNull(store.fhirPayload("astm-v1:a").orElseThrow());
    }

    @Test
    @DisplayName("keeps the first delivery when the same result is rendered again")
    void keepsFirstDeliveryOnReRender() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markDelivered("astm-v1:a", 200, "receipt-1", "{}");

      Receipt again = store.receive(astm(RAW_ASTM));
      store.markRendered(again.id(), List.of(delivery("astm-v1:a", "ACC-1")));

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals(
        OutboxState.DELIVERED,
        entry.state(),
        "an already-delivered result must not be resurrected as pending"
      );
      assertEquals(0, store.countsByState().get(OutboxState.RECEIVED));
    }

    @Test
    @DisplayName("refuses to render nothing")
    void refusesEmptyRender() {
      Receipt receipt = store.receive(astm(RAW_ASTM));
      assertThrows(IllegalArgumentException.class, () -> store.markRendered(receipt.id(), List.of()));
    }
  }

  @Nested
  @DisplayName("dispatch")
  class Dispatch {

    @Test
    @DisplayName("claims due work oldest first and leases it")
    void claimsAndLeases() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      Instant now = Instant.now();

      OutboxEntry claimed = store.claimNextDue(now, Duration.ofMinutes(2), "worker-1").orElseThrow();
      assertEquals("astm-v1:a", claimed.id());
      assertEquals("worker-1", claimed.leaseOwner());
      assertTrue(claimed.leaseUntil().isAfter(now));

      assertTrue(
        store.claimNextDue(now, Duration.ofMinutes(2), "worker-2").isEmpty(),
        "a leased entry must not be claimed twice"
      );
    }

    @Test
    @DisplayName("does not claim before the scheduled retry time")
    void honoursBackoffSchedule() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      Instant now = Instant.now();
      store.claimNextDue(now, Duration.ofMinutes(2), "worker-1");
      store.markRetrying("astm-v1:a", now.plusSeconds(300), null, "java.net.UnknownHostException: oe", null);

      assertTrue(store.claimNextDue(now.plusSeconds(10), Duration.ofMinutes(2), "worker-1").isEmpty());
      assertTrue(store.claimNextDue(now.plusSeconds(301), Duration.ofMinutes(2), "worker-1").isPresent());
    }

    @Test
    @DisplayName("counts an attempt on every delivery outcome")
    void countsAttempts() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      Instant now = Instant.now();

      store.markRetrying("astm-v1:a", now.plusSeconds(5), 503, "service unavailable", "upstream down");
      assertEquals(1, store.get("astm-v1:a").orElseThrow().attempts());
      assertEquals(OutboxState.RETRYING, store.get("astm-v1:a").orElseThrow().state());

      store.markDelivered("astm-v1:a", 200, "receipt-9", "{\"receiptId\":\"receipt-9\"}");
      OutboxEntry delivered = store.get("astm-v1:a").orElseThrow();
      assertEquals(2, delivered.attempts());
      assertEquals(OutboxState.DELIVERED, delivered.state());
      assertEquals("receipt-9", delivered.oeReceipt(), "the OpenELIS receipt is the operator's delivery confirmation");
      assertNull(delivered.nextAttemptAt());
      assertNull(delivered.leaseUntil());
    }

    @Test
    @DisplayName("keeps the payload when a delivery is rejected")
    void keepsPayloadOnRejection() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");

      store.markRejected(
        "astm-v1:a",
        FailureReason.OE_CONFIG_STATE,
        422,
        "missingSiteBinding",
        "{\"errorKey\":\"...\"}"
      );

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals(OutboxState.DMQ, entry.state());
      assertEquals(FailureReason.OE_CONFIG_STATE, entry.failureReason());
      assertEquals(1, entry.attempts());
      assertEquals(
        RAW_ASTM,
        store.rawPayload("astm-v1:a").orElseThrow(),
        "a dead-lettered entry keeps its complete payload"
      );
      assertNotNull(store.fhirPayload("astm-v1:a").orElseThrow());
    }

    @Test
    @DisplayName("dead-letters a pre-delivery failure without counting an attempt")
    void deadLettersWithoutAttempt() {
      Receipt receipt = store.receive(astm(RAW_ASTM));
      store.markDeadLettered(receipt.id(), FailureReason.UNREGISTERED_SOURCE, "no saved connection for 192.168.1.10");

      OutboxEntry entry = store.get(receipt.id()).orElseThrow();
      assertEquals(OutboxState.DMQ, entry.state());
      assertEquals(0, entry.attempts(), "nothing was sent, so nothing was attempted");
      assertEquals(RAW_ASTM, store.rawPayload(receipt.id()).orElseThrow());
    }
  }

  @Nested
  @DisplayName("restart")
  class Restart {

    @Test
    @DisplayName("survives a reopen with its payloads intact")
    void survivesReopen() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.close();

      store = new SqliteOutboxStore(dbPath);

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals(OutboxState.PENDING, entry.state());
      assertEquals(RAW_ASTM, store.rawPayload("astm-v1:a").orElseThrow());
      assertFalse(store.recoveredFromCorruption());
    }

    @Test
    @DisplayName("returns work interrupted mid-attempt to the queue, due now")
    void recoversInterruptedWork() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      Instant crash = Instant.now();
      store.claimNextDue(crash, Duration.ofMinutes(30), "worker-1");
      store.close();

      store = new SqliteOutboxStore(dbPath);
      Instant restart = crash.plusSeconds(5);
      assertEquals(1, store.recoverInterrupted(restart));

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals(OutboxState.PENDING, entry.state());
      assertNull(entry.leaseUntil(), "a lease held by a dead process must not block redelivery");
      assertNull(entry.nextAttemptAt());
      assertTrue(store.claimNextDue(restart, Duration.ofMinutes(2), "worker-2").isPresent());
      assertEquals(
        OutboxAttempt.Kind.STARTUP_RECOVERY,
        store.attempts("astm-v1:a").get(0).kind(),
        "the interruption is recorded so an operator can see why a delivery repeated"
      );
    }

    @Test
    @DisplayName("leaves unleased work alone")
    void leavesUnleasedWorkAlone() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      assertEquals(0, store.recoverInterrupted(Instant.now()));
    }
  }

  @Nested
  @DisplayName("operator actions")
  class OperatorActions {

    @Test
    @DisplayName("makes a dead-lettered entry due again")
    void retriesDeadLetteredEntry() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markRejected("astm-v1:a", FailureReason.RETRY_EXHAUSTED, null, "OpenELIS unreachable", null);
      Instant now = Instant.now();

      store.requestRetry("astm-v1:a", "admin", now);

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals(OutboxState.PENDING, entry.state());
      assertNull(entry.failureReason());
      assertEquals("admin", entry.retryRequestedBy(), "who asked for the retry is part of the audit trail");
      assertTrue(store.claimNextDue(now, Duration.ofMinutes(2), "worker-1").isPresent());
    }

    @Test
    @DisplayName("will not resurrect a delivered entry")
    void refusesToRetryDeliveredEntry() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markDelivered("astm-v1:a", 200, "receipt-1", "{}");

      store.requestRetry("astm-v1:a", "admin", Instant.now());

      assertEquals(OutboxState.DELIVERED, store.get("astm-v1:a").orElseThrow().state());
    }

    @Test
    @DisplayName("re-renders a payload without changing the delivery identity")
    void replacesPayloadOnReRender() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markRejected("astm-v1:a", FailureReason.OE_CONFIG_STATE, 422, "profileMismatch", null);

      store.replaceRenderedPayload("astm-v1:a", "{\"resourceType\":\"Bundle\",\"revised\":true}", "genexpert-astm", 2);

      OutboxEntry entry = store.get("astm-v1:a").orElseThrow();
      assertEquals("astm-v1:a", entry.id(), "the identity OpenELIS deduplicates on must not change");
      assertEquals(2, entry.profileRevision());
      assertTrue(store.fhirPayload("astm-v1:a").orElseThrow().contains("revised"));
    }

    @Test
    @DisplayName("hides a dismissed entry without deleting it")
    void dismissHidesWithoutDeleting() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markRejected("astm-v1:a", FailureReason.OE_REJECTED, 400, "bad request", null);

      store.dismiss("astm-v1:a", "admin", Instant.now());

      assertTrue(store.list(OutboxQuery.inState(OutboxState.DMQ, 100)).isEmpty());
      assertEquals(1, store.list(new OutboxQuery(OutboxState.DMQ, null, null, true, 100, 0)).size());
      assertEquals(RAW_ASTM, store.rawPayload("astm-v1:a").orElseThrow());
    }
  }

  @Nested
  @DisplayName("listing and retention")
  class ListingAndRetention {

    @Test
    @DisplayName("filters by state, connection and failure reason")
    void filters() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      receiveAndRender(RAW_ASTM + "R|2|^^^RIF|NEG||||F\r", "astm-v1:b", "ACC-2");
      store.markRejected("astm-v1:b", FailureReason.OE_CONFIG_STATE, 422, "missingSiteBinding", null);

      assertEquals(1, store.list(OutboxQuery.inState(OutboxState.PENDING, 100)).size());
      assertEquals(
        1,
        store.list(new OutboxQuery(OutboxState.DMQ, null, FailureReason.OE_CONFIG_STATE, false, 100, 0)).size()
      );
      assertEquals(0, store.list(new OutboxQuery(null, "some-other-connection", null, false, 100, 0)).size());
      assertEquals(2, store.list(OutboxQuery.all(100)).size());
    }

    @Test
    @DisplayName("reports the oldest entry still awaiting delivery")
    void reportsOldestUndelivered() {
      assertTrue(store.oldestUndeliveredReceivedAt().isEmpty());
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      Optional<Instant> oldest = store.oldestUndeliveredReceivedAt();
      assertTrue(oldest.isPresent());

      store.markDelivered("astm-v1:a", 200, "receipt-1", "{}");
      assertTrue(store.oldestUndeliveredReceivedAt().isEmpty(), "delivered work is no longer waiting");
    }

    @Test
    @DisplayName("never purges an undelivered result, however old")
    void neverPurgesUndelivered() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markRejected("astm-v1:a", FailureReason.RETRY_EXHAUSTED, null, "OpenELIS unreachable", null);

      int removed = store.purgeExpired(
        Instant.now().plus(Duration.ofDays(3650)),
        Duration.ofDays(30),
        Duration.ofDays(90)
      );

      assertEquals(0, removed);
      assertEquals(
        RAW_ASTM,
        store.rawPayload("astm-v1:a").orElseThrow(),
        "an undismissed dead letter is still evidence"
      );
    }

    @Test
    @DisplayName("purges delivered and dismissed entries past their window, with their content")
    void purgesExpiredEntries() {
      receiveAndRender(RAW_ASTM, "astm-v1:a", "ACC-1");
      store.markDelivered("astm-v1:a", 200, "receipt-1", "{}");

      int removed = store.purgeExpired(
        Instant.now().plus(Duration.ofDays(40)),
        Duration.ofDays(30),
        Duration.ofDays(90)
      );

      assertEquals(1, removed);
      assertTrue(store.get("astm-v1:a").isEmpty());
      assertTrue(store.rawPayload("astm-v1:a").isEmpty(), "orphaned content must not linger after its entry is gone");
    }
  }
}
