package org.itech.ahb.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxEntry;
import org.itech.ahb.outbox.OutboxQuery;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to a received result on its way to OpenELIS, exercised end to end against a stub
 * OpenELIS on a real socket and a real tempdir-backed outbox: no mocks at the persistence boundary,
 * and no mocks at the network boundary either.
 *
 * <p>The behavior under test is the one the Madagascar incident broke. Before the outbox, a forward
 * failure kept an 800-character snippet and dropped the message; these tests assert that every
 * outcome now leaves the complete message somewhere an operator can reach it.
 */
class HttpForwardingRouterTest {

  private HttpServer server;
  private int port;
  private AtomicInteger statusCodeToReturn;
  private AtomicInteger requestCount;
  private AtomicReference<String> requestBody;
  private AtomicReference<String> requestPath;
  private Path tmpDir;
  private OutboxTestSupport pipeline;

  @BeforeEach
  void setUp(@TempDir Path tmp) throws IOException {
    this.tmpDir = tmp;
    statusCodeToReturn = new AtomicInteger(200);
    requestCount = new AtomicInteger();
    requestBody = new AtomicReference<>();
    requestPath = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    port = server.getAddress().getPort();
    server.createContext("/analyzer", exchange -> {
      requestCount.incrementAndGet();
      requestPath.set(exchange.getRequestURI().getPath());
      requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
      int code = statusCodeToReturn.get();
      byte[] body = ("status " + code).getBytes();
      exchange.sendResponseHeaders(code, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (pipeline != null) {
      pipeline.close();
    }
  }

  @Test
  @DisplayName("an accepted delivery is recorded as delivered, with OpenELIS's answer")
  void acceptedDeliveryIsRecorded() {
    pipeline = registeredPipeline("10.0.0.6");
    statusCodeToReturn.set(200);

    assertTrue(pipeline.receiveAndDeliver(envelope("10.0.0.6")));

    assertEquals(1, requestCount.get());
    OutboxEntry entry = onlyEntry();
    assertEquals(OutboxState.DELIVERED, entry.state());
    assertEquals(200, entry.lastHttpStatus());
    assertEquals(1, entry.attempts());
    assertNotNull(entry.oeReceipt(), "an operator confirms delivery against OpenELIS, not against our log");
  }

  @Test
  @DisplayName("OpenELIS being unreachable keeps the complete message and schedules a retry")
  void unreachableOpenElisKeepsTheMessage() {
    pipeline = registeredPipeline("10.0.0.9");
    server.stop(0);
    server = null;

    assertTrue(
      pipeline.receiveAndDeliver(envelope("10.0.0.9")),
      "the analyzer's session is over; the bridge reports receipt because it has the result, not because OpenELIS does"
    );

    OutboxEntry entry = onlyEntry();
    assertEquals(OutboxState.RETRYING, entry.state());
    assertEquals(1, entry.attempts());
    assertNotNull(entry.nextAttemptAt());
    String raw = pipeline.store.rawPayload(entry.id()).orElseThrow();
    assertTrue(raw.contains("R|1|^^^WBC|7.5"), "the message as received is kept whole, not as a snippet");
    assertTrue(pipeline.store.fhirPayload(entry.id()).orElseThrow().contains("Bundle"));
  }

  @Test
  @DisplayName("a delivery is redelivered byte-identically until OpenELIS accepts it")
  void retriesUntilAccepted() {
    pipeline = registeredPipeline("10.0.0.10");
    statusCodeToReturn.set(503);

    pipeline.receiveAndDeliver(envelope("10.0.0.10"));
    String firstBody = requestBody.get();
    assertEquals(OutboxState.RETRYING, onlyEntry().state());

    statusCodeToReturn.set(200);
    awaitBackoff();
    pipeline.dispatcher.dispatchDue();

    assertEquals(2, requestCount.get());
    assertEquals(firstBody, requestBody.get(), "a retry must carry the identity OpenELIS deduplicates on");
    OutboxEntry entry = onlyEntry();
    assertEquals(OutboxState.DELIVERED, entry.state());
    assertEquals(2, entry.attempts());
  }

  @Test
  @DisplayName("OpenELIS rejecting the delivery holds it for an operator instead of retrying")
  void rejectionIsHeldForAnOperator() {
    pipeline = registeredPipeline("100.127.144.150");
    statusCodeToReturn.set(401);

    pipeline.receiveAndDeliver(envelope("100.127.144.150"));

    OutboxEntry entry = onlyEntry();
    assertEquals(OutboxState.DMQ, entry.state());
    assertEquals(FailureReason.OE_REJECTED, entry.failureReason());
    assertEquals(401, entry.lastHttpStatus());
    assertEquals("100.127.144.150", entry.sourceId());
    assertEquals(Protocol.ASTM, entry.protocol());
    assertTrue(pipeline.store.rawPayload(entry.id()).orElseThrow().contains("SAMPLE-1"));
  }

  @Test
  @DisplayName("an OpenELIS configuration refusal is named as such, so the operator knows where to fix it")
  void configurationRefusalIsNamed() {
    pipeline = registeredPipeline("10.0.0.11");
    statusCodeToReturn.set(422);

    pipeline.receiveAndDeliver(envelope("10.0.0.11"));

    OutboxEntry entry = onlyEntry();
    assertEquals(OutboxState.DMQ, entry.state());
    assertEquals(FailureReason.OE_CONFIG_STATE, entry.failureReason());
    assertEquals(1, requestCount.get(), "a configuration refusal will not resolve itself, so it is not retried");
  }

  @Test
  @DisplayName("an analyzer with no pinned recognition is held, not forwarded")
  void unpinnedAnalyzerIsHeld() {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("analyzer-1");
    entry.setExpectedProtocol("ASTM");
    registry.register("10.0.0.7", entry);
    pipeline = OutboxTestSupport.create(tmpDir, minimalConfig(), registry);

    pipeline.receiveAndDeliver(envelope("10.0.0.7"));

    assertEquals(0, requestCount.get(), "traffic without a pinned-profile recognition mode must not be forwarded");
    OutboxEntry held = onlyEntry();
    assertEquals(OutboxState.DMQ, held.state());
    assertEquals(FailureReason.UNPINNED_PROFILE, held.failureReason());
    assertTrue(pipeline.store.rawPayload(held.id()).orElseThrow().contains("SAMPLE-1"));
  }

  @Test
  @DisplayName("an ASTM analyzer with no pinned result selection is held, not forwarded")
  void astmWithoutResultSelectionIsHeld() {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("analyzer-1");
    entry.setExpectedProtocol("ASTM");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    registry.register("10.0.0.8", entry);
    pipeline = OutboxTestSupport.create(tmpDir, minimalConfig(), registry);

    pipeline.receiveAndDeliver(envelope("10.0.0.8"));

    assertEquals(0, requestCount.get());
    OutboxEntry held = onlyEntry();
    assertEquals(OutboxState.DMQ, held.state());
    assertEquals(FailureReason.UNPINNED_PROFILE, held.failureReason());
    assertTrue(held.lastError().contains("result-record selection"));
  }

  @Test
  @DisplayName("a message that parses to nothing is held with its content for diagnosis")
  void unparseableMessageIsHeld() {
    for (String raw : new String[] { "H|\\^&|||Analyzer\rL|1", "", "   " }) {
      pipeline = registeredPipeline("10.0.0.20");
      MessageEnvelope message = MessageEnvelope.builder()
        .protocol(Protocol.ASTM)
        .transport(Transport.HTTP)
        .sourceId("10.0.0.20")
        .resolvedAnalyzerId("analyzer-1")
        .rawMessage(raw)
        .build();

      if (raw.isBlank()) {
        // Nothing was received, so there is nothing to keep; the transport refuses it outright.
        assertFalse(pipeline.receive(message));
      } else {
        pipeline.receiveAndDeliver(message);
        OutboxEntry held = onlyEntry();
        assertEquals(OutboxState.DMQ, held.state());
        assertEquals(FailureReason.PARSE_NO_RESULTS, held.failureReason());
        assertEquals(raw, pipeline.store.rawPayload(held.id()).orElseThrow());
      }
      assertEquals(0, requestCount.get());
      pipeline.close();
      pipeline = null;
    }
  }

  @Test
  @DisplayName("the delivered bundle carries the pinned connection identity and analyzer-native codes")
  void deliveredBundlePreservesConnectionContext() {
    pipeline = registeredPipeline("10.0.0.30");
    statusCodeToReturn.set(200);

    assertTrue(pipeline.receiveAndDeliver(envelope("10.0.0.30")));

    assertEquals("/analyzer/fhir", requestPath.get());
    Bundle bundle = FhirContext.forR4().newJsonParser().parseResource(Bundle.class, requestBody.get());
    Device device = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Device.class::isInstance)
      .map(Device.class::cast)
      .findFirst()
      .orElseThrow();
    Observation observation = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .findFirst()
      .orElseThrow();
    assertEquals(
      "bridge-connection-7f3c",
      device
        .getIdentifier()
        .stream()
        .filter(value -> "https://openelis-global.org/fhir/analyzer-connection-id".equals(value.getSystem()))
        .findFirst()
        .orElseThrow()
        .getValue()
    );
    assertTrue(
      observation
        .getCode()
        .getCoding()
        .stream()
        .anyMatch(
          value ->
            "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code".equals(value.getSystem()) &&
            "WBC".equals(value.getCode())
        )
    );
    assertEquals(
      onlyEntry().id(),
      bundle.getIdentifier().getValue(),
      "the bundle identity and the outbox identity are the same value, which is what makes a retry safe"
    );
  }

  /** The test pipeline retries after a millisecond; wait past it rather than racing the clock. */
  private static void awaitBackoff() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private OutboxEntry onlyEntry() {
    List<OutboxEntry> entries = pipeline.store.list(OutboxQuery.all(10));
    assertEquals(1, entries.size(), "expected exactly one outbox entry");
    return entries.get(0);
  }

  private HTTPForwardServerConfigurationProperties minimalConfig() {
    HTTPForwardServerConfigurationProperties c = new HTTPForwardServerConfigurationProperties();
    c.setUri(URI.create("http://localhost:" + port + "/analyzer"));
    c.setConnectTimeoutSeconds(2);
    c.setReadTimeoutSeconds(2);
    return c;
  }

  private OutboxTestSupport registeredPipeline(String sourceId) {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("analyzer-1");
    entry.setBridgeConnectionId("bridge-connection-7f3c");
    entry.setProfileId("site.mock-hematology");
    entry.setProfileRevision(3);
    entry.setExpectedProtocol("ASTM");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setAstmResultRecordSelection(AstmResultRecordSelection.all());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    registry.register(sourceId, entry);
    return OutboxTestSupport.create(tmpDir, minimalConfig(), registry);
  }

  private MessageEnvelope envelope(String sourceId) {
    return MessageEnvelope.builder()
      .protocol(Protocol.ASTM)
      .transport(Transport.HTTP)
      .sourceId(sourceId)
      .resolvedAnalyzerId("analyzer-1")
      .rawMessage("H|\\^&|||Analyzer\rP|1\rO|1|SAMPLE-1\rR|1|^^^WBC|7.5|10*3/uL\rL|1")
      .build();
  }
}
