package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.routing.NormalizedBundleRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dispatcher's job is that an entry ends DELIVERED or in the dead-message queue, never lost and
 * never stuck. These tests drive it with a scripted OpenELIS so each outcome is exercised exactly.
 */
class OutboxDispatcherTest {

  /** A stand-in OpenELIS that answers from a script and records what it was sent. */
  private static final class ScriptedOpenElis extends FhirDeliveryClient {

    private final Deque<DeliveryOutcome> script = new ArrayDeque<>();
    private final List<String> sent = new ArrayList<>();

    private ScriptedOpenElis(HTTPForwardServerConfigurationProperties config) {
      super(config);
    }

    static ScriptedOpenElis create() {
      HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
      config.setUri(URI.create("http://localhost:1/analyzer"));
      config.setConnectTimeoutSeconds(1);
      config.setReadTimeoutSeconds(1);
      return new ScriptedOpenElis(config);
    }

    ScriptedOpenElis answering(DeliveryOutcome... outcomes) {
      script.addAll(List.of(outcomes));
      return this;
    }

    @Override
    public DeliveryOutcome deliver(String fhirJson) {
      sent.add(fhirJson);
      return script.isEmpty() ? DeliveryOutcome.responded(200, "{}") : script.poll();
    }
  }

  @TempDir
  Path dir;

  private SqliteOutboxStore store;
  private OutboxProperties properties;

  @BeforeEach
  void setUp() {
    store = new SqliteOutboxStore(dir.resolve("outbox.db"));
    properties = new OutboxProperties();
    properties.getRetry().setJitter(0.0);
    properties.getRetry().setBaseDelay(Duration.ZERO);
    properties.getRetry().setMaxDelay(Duration.ZERO);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private OutboxDispatcher dispatcher(ScriptedOpenElis openElis) {
    return new OutboxDispatcher(
      store,
      openElis,
      new NormalizedBundleRenderer(new AnalyzerRuntimeRegistry()),
      properties
    );
  }

  private String queueOneDelivery() {
    Receipt receipt = store.receive(
      new ReceivedMessage(
        "10.0.0.1",
        12001,
        Protocol.ASTM,
        Transport.TCP,
        null,
        "H|raw\rR|1|^^^WBC|7.5\rL|1",
        null,
        Instant.now()
      )
    );
    String id = "astm-v1:" + "a".repeat(64);
    store.markRendered(
      receipt.id(),
      List.of(
        new RenderedDelivery(
          id,
          "ACC-1",
          "{\"resourceType\":\"Bundle\"}",
          "conn-1",
          "analyzer-1",
          "profile",
          1,
          "http://oe/fhir"
        )
      )
    );
    return id;
  }

  private void awaitBackoff() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("delivers and records OpenELIS's receipt reference")
  void deliversAndRecordsReceipt() {
    String id = queueOneDelivery();
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(DeliveryOutcome.responded(200, "{\"success\":true,\"receiptId\":\"rcpt-123\",\"resultsStaged\":1}"));

    assertEquals(1, dispatcher(openElis).dispatchDue());

    OutboxEntry entry = store.get(id).orElseThrow();
    assertEquals(OutboxState.DELIVERED, entry.state());
    assertEquals("rcpt-123", entry.oeReceipt());
    assertEquals(1, entry.attempts());
    assertEquals("DELIVERED", store.attempts(id).get(0).outcome());
  }

  @Test
  @DisplayName("keeps trying a DNS failure and delivers once OpenELIS is reachable again")
  void survivesAnOutage() {
    String id = queueOneDelivery();
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(
        DeliveryOutcome.failed(new UnknownHostException("openelisglobal-webapp")),
        DeliveryOutcome.failed(new UnknownHostException("openelisglobal-webapp")),
        DeliveryOutcome.responded(200, "{\"receiptId\":\"rcpt-9\"}")
      );
    OutboxDispatcher dispatcher = dispatcher(openElis);

    dispatcher.dispatchDue();
    OutboxEntry afterFirst = store.get(id).orElseThrow();
    assertEquals(OutboxState.RETRYING, afterFirst.state());
    assertTrue(afterFirst.lastError().contains("UnknownHostException"), "the operator needs to see why, not just that");

    awaitBackoff();
    dispatcher.dispatchDue();
    awaitBackoff();
    dispatcher.dispatchDue();

    OutboxEntry delivered = store.get(id).orElseThrow();
    assertEquals(OutboxState.DELIVERED, delivered.state());
    assertEquals(3, delivered.attempts());
    assertEquals(3, openElis.sent.size());
    assertEquals(1, openElis.sent.stream().distinct().count(), "every attempt must send byte-identical content");
  }

  @Test
  @DisplayName("holds the complete result once the retry budget is spent")
  void deadLettersAfterTheRetryBudget() {
    String id = queueOneDelivery();
    properties.getRetry().setMaxAttempts(3);
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(
        DeliveryOutcome.failed(new UnknownHostException("oe")),
        DeliveryOutcome.failed(new UnknownHostException("oe")),
        DeliveryOutcome.failed(new UnknownHostException("oe"))
      );
    OutboxDispatcher dispatcher = dispatcher(openElis);

    for (int i = 0; i < 3; i++) {
      awaitBackoff();
      dispatcher.dispatchDue();
    }

    OutboxEntry entry = store.get(id).orElseThrow();
    assertEquals(OutboxState.DMQ, entry.state());
    assertEquals(FailureReason.RETRY_EXHAUSTED, entry.failureReason());
    assertEquals(3, entry.attempts());
    assertNotNull(store.rawPayload(id).orElseThrow(), "exhausting retries must never cost the payload");
    assertNotNull(store.fhirPayload(id).orElseThrow());
  }

  @Test
  @DisplayName("an operator retry after the outage delivers the held result")
  void operatorRetryDeliversAfterTheOutage() {
    String id = queueOneDelivery();
    properties.getRetry().setMaxAttempts(1);
    OutboxDispatcher dispatcher = dispatcher(
      ScriptedOpenElis.create().answering(DeliveryOutcome.failed(new UnknownHostException("oe")))
    );
    dispatcher.dispatchDue();
    assertEquals(OutboxState.DMQ, store.get(id).orElseThrow().state());

    store.requestRetry(id, "admin", Instant.now());
    properties.getRetry().setMaxAttempts(5);
    dispatcher(
      ScriptedOpenElis.create().answering(DeliveryOutcome.responded(200, "{\"receiptId\":\"rcpt-1\"}"))
    ).dispatchDue();

    assertEquals(OutboxState.DELIVERED, store.get(id).orElseThrow().state());
  }

  @Test
  @DisplayName("stops retrying what OpenELIS has refused, and says which refusal it was")
  void stopsRetryingARefusal() {
    String id = queueOneDelivery();
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(DeliveryOutcome.responded(422, "{\"errorKey\":\"analyzer.fhirImport.error.profileMismatch\"}"));

    dispatcher(openElis).dispatchDue();
    awaitBackoff();
    dispatcher(openElis).dispatchDue();

    OutboxEntry entry = store.get(id).orElseThrow();
    assertEquals(OutboxState.DMQ, entry.state());
    assertEquals(FailureReason.OE_CONFIG_STATE, entry.failureReason());
    assertEquals(1, openElis.sent.size(), "a configuration refusal will not resolve itself, so it is sent once");
    assertTrue(entry.lastResponseExcerpt().contains("profileMismatch"));
  }

  @Test
  @DisplayName("treats OpenELIS accepting a repeat as delivered, which is what makes retrying safe")
  void duplicateAcknowledgementIsDelivered() {
    String id = queueOneDelivery();
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(
        DeliveryOutcome.failed(new UnknownHostException("oe")),
        // OpenELIS accepted the first attempt; only its answer was lost. It replies 200 with the
        // receipt it already recorded.
        DeliveryOutcome.responded(200, "{\"success\":true,\"receiptId\":\"rcpt-first\",\"resultsStaged\":1}")
      );
    OutboxDispatcher dispatcher = dispatcher(openElis);

    dispatcher.dispatchDue();
    awaitBackoff();
    dispatcher.dispatchDue();

    OutboxEntry entry = store.get(id).orElseThrow();
    assertEquals(OutboxState.DELIVERED, entry.state());
    assertEquals("rcpt-first", entry.oeReceipt());
  }

  @Test
  @DisplayName("does not spin on one entry while everything behind it waits")
  void makesOnePassPerDispatch() {
    queueOneDelivery();
    ScriptedOpenElis openElis = ScriptedOpenElis.create()
      .answering(DeliveryOutcome.failed(new UnknownHostException("oe")));

    dispatcher(openElis).dispatchDue();

    assertEquals(1, openElis.sent.size(), "a zero backoff must not turn one pass into a hot loop");
  }

  @Test
  @DisplayName("returns work interrupted by a restart and redelivers it")
  void recoversWorkInterruptedByARestart() {
    String id = queueOneDelivery();
    store.claimNextDue(Instant.now(), Duration.ofMinutes(30), "dispatcher-from-a-dead-process");

    assertEquals(1, store.recoverInterrupted(Instant.now()));
    ScriptedOpenElis openElis = ScriptedOpenElis.create().answering(DeliveryOutcome.responded(200, "{}"));
    dispatcher(openElis).dispatchDue();

    assertEquals(OutboxState.DELIVERED, store.get(id).orElseThrow().state());
    assertFalse(openElis.sent.isEmpty());
  }
}
