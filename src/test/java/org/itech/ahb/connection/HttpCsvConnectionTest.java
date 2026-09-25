package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.controller.AnalyzerInputController;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxQuery;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real publication, saved runtime, SQLite and forwarding HTTP receiver. Inbound MockMvc covers
 * controller/source-address handling, not deployed HTTP sockets, proxy configuration or OE2 clinical ingestion. */
class HttpCsvConnectionTest {

  private OutboxTestSupport outbox;

  @TempDir
  Path directory;

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<ObjectNode> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger rejectDeliveryNumber = new AtomicInteger();
  private final AtomicBoolean unavailable = new AtomicBoolean();
  private final FileWatcher watcher = mock(FileWatcher.class);
  private HttpServer receiver;
  private AnalyzerProfileCatalog profiles;
  private AnalyzerConnectionCatalog connections;
  private AnalyzerRuntimeRegistry registry;
  private MockMvc input;
  private ObjectNode profile;

  @BeforeEach
  void setUp() throws Exception {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/analyzer/fhir", exchange -> {
      received.add((ObjectNode) mapper.readTree(exchange.getRequestBody()));
      exchange.sendResponseHeaders(unavailable.get() || received.size() == rejectDeliveryNumber.get() ? 503 : 200, -1);
      exchange.close();
    });
    receiver.start();
    profile = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/analyzer-profiles/fluorocycler-xt.json"));
    profile.withObject("profileMeta").put("id", "test.http-tabular");
    profile.withObject("protocol").put("format", "CSV");
    profile.putArray("supported_extensions").add(".csv");
    profile.remove("sheet_detection");
    profile.putArray("result_value_order").add("result").add("interpretation");
    profile.withObject("capabilities").put("connectionTest", false);
    profile
      .withObject("configDefaults")
      .put("fileFormat", "CSV")
      .put("filePattern", "*.csv")
      .put("delimiter", ";")
      .put("skipRows", 1);
    var fields = profile.putArray("connectionFields");
    var transport = fields
      .addObject()
      .put("key", "transport")
      .put("labelKey", "analyzer.connection.field.transport")
      .put("inputKind", "SELECT")
      .put("required", true);
    transport.putArray("choices").addObject().put("value", "HTTP").put("labelKey", "analyzer.transport.http");
    fields
      .addObject()
      .put("key", "host")
      .put("labelKey", "analyzer.connection.field.host")
      .put("inputKind", "TEXT")
      .put("required", true)
      .putArray("choices");
    profile
      .withObject("catalog")
      .put("revisionFingerprint", new ProfileFingerprintService().revisionFingerprint(profile));
    assertThat(
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(mapper.readTree(Path.of("contracts/analyzer/v1/analyzer-profile.schema.json").toFile()))
        .validate(profile)
    ).isEmpty();
    profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(new ByteArrayResource(mapper.writeValueAsBytes(profile))),
      mapper,
      Clock.systemUTC()
    );
    reopen();
  }

  private void reopen() {
    if (outbox != null) outbox.close();
    registry = new AnalyzerRuntimeRegistry();
    var runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      watcher,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    connections = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      mapper,
      Clock.systemUTC(),
      UUID::randomUUID,
      runtime
    );
    var config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    config.setMaxAttempts(1);
    config.setConnectTimeoutSeconds(2);
    config.setReadTimeoutSeconds(2);
    outbox = OutboxTestSupport.create(directory.resolve("outbox"), config, registry);
    input = MockMvcBuilders.standaloneSetup(
      new AnalyzerInputController(outbox.normalizer(new AnalyzerIdentifier(registry), registry))
    ).build();
  }

  @AfterEach
  void stop() {
    if (outbox != null) outbox.close();
    if (receiver != null) receiver.stop(0);
  }

  @Test
  void receivesRealTsvThroughThePinnedTabularParser() throws Exception {
    profile.withObject("protocol").put("format", "TSV");
    profile.putArray("supported_extensions").add(".tsv");
    profile.withObject("configDefaults").put("fileFormat", "TSV").put("filePattern", "*.tsv").put("delimiter", "\t");
    profile
      .withObject("catalog")
      .put("revisionFingerprint", new ProfileFingerprintService().revisionFingerprint(profile));
    profiles = new AnalyzerProfileCatalog(
      directory.resolve("tsv-profiles"),
      List.of(new ByteArrayResource(mapper.writeValueAsBytes(profile))),
      mapper,
      Clock.systemUTC()
    );
    reopen();
    activate("oe-http-tsv", "192.0.2.25");
    String tsv =
      "Export metadata\nSample ID\tTargetName\tCalc. Conc.\tInterpretation\tType\nSAMPLE-TSV\tVIH-1\t17.5\t\tPatient\n";
    var response = input
      .perform(
        post("/input")
          .contentType("text/tab-separated-values")
          .content(tsv)
          .with(request -> {
            request.setRemoteAddr("192.0.2.25");
            return request;
          })
      )
      .andReturn()
      .getResponse();
    outbox.dispatcher.dispatchDue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(received).hasSize(1);
    assertThat(received.get(0).path("identifier").path("value").asText()).startsWith("file-v1:");
    assertThat(received.get(0).toString()).contains("SAMPLE-TSV", "17.5", "VIH-1");
    verifyNoInteractions(watcher);
  }

  @Test
  void receivesRealCsvWithProfileDefaultsAndRestoresTheSameDeliveryIdentity() throws Exception {
    ObjectNode connection = activate("oe-http", "192.0.2.25");
    assertThat(send("192.0.2.25", null)).isEqualTo(200);
    assertThat(received).hasSize(2);
    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
      mapper.readTree(Path.of("contracts/analyzer/v1/normalized-fhir-bundle.schema.json").toFile())
    );
    received.forEach(bundle -> assertThat(schema.validate(bundle)).isEmpty());
    assertThat(received.toString()).contains(
      "PATIENT",
      "CONTROL",
      "VIH-1",
      "17.5",
      "Detected",
      connection.path("connectionId").asText(),
      "test.http-tabular"
    );
    var firstIds = received.stream().map(bundle -> bundle.path("identifier").path("value").asText()).toList();
    assertThat(firstIds).hasSize(2).doesNotHaveDuplicates().allMatch(id -> id.startsWith("file-v1:"));
    reopen();
    assertThat(send("192.0.2.25", null)).isEqualTo(200);
    assertThat(received).hasSize(2); // A delivered receipt survives restart and is not forwarded again.
    assertThat(outbox.store.list(OutboxQuery.inState(OutboxState.DELIVERED, 10)))
      .extracting(entry -> {
        try {
          return mapper
            .readTree(outbox.store.fhirPayload(entry.id()).orElseThrow())
            .path("identifier")
            .path("value")
            .asText();
        } catch (Exception error) {
          throw new AssertionError(error);
        }
      })
      .containsExactlyInAnyOrderElementsOf(firstIds);
    verifyNoInteractions(watcher);
  }

  @Test
  void retriesARejectedAccessionWithItsOriginalIdentityAndLeavesTheAcceptedOneAlone() {
    activate("oe-http", "192.0.2.25");
    rejectDeliveryNumber.set(2);

    // The request succeeds because the bridge durably holds both results, not because OpenELIS took
    // them. That is the change the outbox makes: receipt and delivery are separate answers.
    assertThat(send("192.0.2.25", null)).isEqualTo(200);
    assertThat(received).hasSize(2);
    String acceptedId = received.get(0).path("identifier").path("value").asText();
    String rejectedId = received.get(1).path("identifier").path("value").asText();

    rejectDeliveryNumber.set(0);
    sleepPastBackoff();
    outbox.dispatcher.dispatchDue();

    assertThat(received).hasSize(3);
    assertThat(received.get(2).path("identifier").path("value").asText())
      .as("only the accession OpenELIS refused is sent again, and it keeps the identity OpenELIS deduplicates on")
      .isEqualTo(rejectedId)
      .isNotEqualTo(acceptedId);
  }

  private static void sleepPastBackoff() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void rejectsUnknownAndAmbiguousSendersAndRestoresPeerAfterDeactivation() {
    activate("oe-http-a", "192.0.2.25");
    assertThat(send("192.0.2.99", "192.0.2.25")).isNotEqualTo(200);
    ObjectNode peer = activate("oe-http-b", "192.0.2.25");
    assertThat(send("192.0.2.25", null)).isNotEqualTo(200);
    assertThat(received).isEmpty();
    connections.applyRuntimeCommand(command(peer, "DEACTIVATE"));
    assertThat(send("192.0.2.25", null)).isEqualTo(200);
    assertThat(received).hasSize(2);
    verifyNoInteractions(watcher);
  }

  @ParameterizedTest
  @CsvSource({ "2001:db8::1,2001:db8:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1,2001:db8::1" })
  void equivalentIpv6SendersMatchSavedConnectionsAfterRestore(String configured, String observed) {
    activate("oe-ipv6", configured);
    assertThat(send(observed, null)).isEqualTo(200);
    assertThat(received).hasSize(2);
    reopen();
    assertThat(send(observed, null)).isEqualTo(200);
    assertThat(received).hasSize(2);

    activate("oe-ipv6-duplicate", observed);
    assertThat(send(observed, null)).isNotEqualTo(200);
    assertThat(received).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(strings = { "analyzer.example.org", "192.0.2.25:8080", "192.0.2.999", "127.1", "2001:db8::1/64" })
  void rejectsNonLiteralHttpHostsBeforeSavingTheConnection(String host) {
    assertThatThrownBy(() -> create("oe-invalid-http", host))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("IP address");
    assertThat(registry.getRegisteredAnalyzers()).isEmpty();
    assertThat(received).isEmpty();
  }

  @Test
  void invalidPartialHostUpdatePreservesTheActiveHttpConnection() {
    ObjectNode saved = activate("oe-http-update", "192.0.2.25");
    ObjectNode update = mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", "invalid-host-update")
      .put("connectionId", saved.path("connectionId").asText())
      .put("expectedConfigRevision", 1)
      .put("displayName", "oe-http-update");
    update.set("profileRef", saved.path("profileRef").deepCopy());
    update.putObject("values").put("host", "analyzer.example.org");

    assertThatThrownBy(() -> connections.update(update))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("IP address");

    reopen();
    assertThat(send("192.0.2.25", null)).isEqualTo(200);
    assertThat(received).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7" })
  void publishedHttpSocketProfileRetainsOriginalResultsAcrossOutageRestartAndRetry(String protocol) throws Exception {
    publishHttpProfile(protocol);
    ObjectNode connection = activate("oe-http-socket", "192.0.2.25");
    String raw = socketMessage(protocol, "HTTP-ORIGINAL");
    unavailable.set(true);
    assertThat(postSocket(protocol, raw, "192.0.2.25", null)).isEqualTo(200);
    assertThat(received).isEmpty(); // Durable receipt precedes even the first delivery attempt.
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(1);
    var held = outbox.store.list(OutboxQuery.all(10)).get(0);
    assertThat(held.state()).isEqualTo(OutboxState.RETRYING);
    assertThat(outbox.store.rawPayload(held.id())).contains(raw);
    JsonNode first = received.get(0);
    assertSocketBundle(first, connection, "HTTP-ORIGINAL");

    profiles = new AnalyzerProfileCatalog(directory.resolve("profiles"), List.of(), mapper, Clock.systemUTC());
    reopen();
    assertThat(connections.require(connection.path("connectionId").asText()).path("profileRef")).isEqualTo(
      connection.path("profileRef")
    );
    assertThat(outbox.store.rawPayload(held.id())).contains(raw);
    // Exhaust a small real retry budget before an operator replays the retained result.
    outbox.properties.getRetry().setMaxAttempts(2);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (outbox.store.get(held.id()).orElseThrow().state() != OutboxState.DMQ && System.nanoTime() < deadline) {
      outbox.dispatcher.dispatchDue();
      Thread.yield();
    }
    assertThat(outbox.store.get(held.id()).orElseThrow().state()).isEqualTo(OutboxState.DMQ);
    assertThat(received).hasSize(2);
    unavailable.set(false);
    outbox.store.requestRetry(held.id(), "operator", Instant.now());
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(3);
    assertThat(received).allSatisfy(bundle -> {
      assertThat(bundle.path("identifier")).isEqualTo(first.path("identifier"));
      assertSocketBundle(bundle, connection, "HTTP-ORIGINAL");
    });
    assertThat(outbox.store.get(held.id()).orElseThrow().state()).isEqualTo(OutboxState.DELIVERED);
    assertThat(postSocket(protocol, raw, "192.0.2.25", null)).isEqualTo(200);
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(3); // Repeated instrument transmission cannot create a new delivery.

    String fresh = socketMessage(protocol, "HTTP-AFTER-RESTART");
    assertThat(postSocket(protocol, fresh, "192.0.2.25", null)).isEqualTo(200);
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(4);
    assertSocketBundle(received.get(3), connection, "HTTP-AFTER-RESTART");
    assertThat(received.get(3).path("identifier")).isNotEqualTo(first.path("identifier"));
    connections.applyRuntimeCommand(command(connection, "DEACTIVATE"));
    String after = socketMessage(protocol, "AFTER-DEACTIVATE");
    assertThat(postSocket(protocol, after, "192.0.2.25", null)).isNotEqualTo(200);
    assertRetainedFailure(after, FailureReason.UNREGISTERED_SOURCE);
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(4);
    verifyNoInteractions(watcher);
  }

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7" })
  void httpSocketProbeNeverClaimsToTestAnUnrelatedTcpListener(String protocol) throws Exception {
    publishHttpProfile(protocol);
    ObjectNode connection = activate("oe-http-probe", "192.0.2.25");
    var executor = mock(ConnectionProbeExecutor.class);
    var probe = new AnalyzerConnectionProbe(mapper, Clock.systemUTC(), executor, (name, port) -> {
      throw new AssertionError("HTTP has no analyzer TCP listener to test");
    });
    JsonNode result = connections.probe(
      mapper
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("requestId", "http-probe")
        .put("connectionId", connection.path("connectionId").asText())
        .put("expectedConfigRevision", connection.path("configRevision").asInt()),
      probe
    );
    assertThat(result.path("checks")).hasSize(1);
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("http.input.verify.with.delivery");
    assertThat(result.path("status").asText()).isNotEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("http-input");
    // Existing contract maps an unsupported automatic check to FAILED, with a reason-specific key.
    assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("FAILED");
    verifyNoInteractions(executor);
  }

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7" })
  void httpSocketIdentityFailuresRetainRawInputWithoutForwarding(String protocol) throws Exception {
    publishHttpProfile(protocol);
    activate("oe-http-source", "192.0.2.25");
    String unknown = socketMessage(protocol, "UNKNOWN-PEER");
    assertThat(postSocket(protocol, unknown, "192.0.2.99", "192.0.2.25")).isNotEqualTo(200);
    assertRetainedFailure(unknown, FailureReason.UNREGISTERED_SOURCE);
    String opposite = protocol.equals("ASTM") ? "HL7" : "ASTM";
    String mismatch = socketMessage(opposite, "WRONG-PROTOCOL");
    assertThat(postSocket(opposite, mismatch, "192.0.2.25", null)).isNotEqualTo(200);
    assertRetainedFailure(mismatch, FailureReason.CONNECTION_TRANSPORT_MISMATCH);
    var mismatchedReceipt = outbox.store
      .list(OutboxQuery.inState(OutboxState.DMQ, 10))
      .stream()
      .filter(entry -> outbox.store.rawPayload(entry.id()).orElse("").equals(mismatch))
      .findFirst()
      .orElseThrow();
    reopen();
    outbox.store.requestRetry(mismatchedReceipt.id(), "operator", Instant.now());
    outbox.dispatcher.dispatchDue();
    assertRetainedFailure(mismatch, FailureReason.CONNECTION_TRANSPORT_MISMATCH);
    assertThat(received).isEmpty(); // Operator retry cannot bypass the saved protocol/transport contract.

    activate("oe-http-duplicate", "192.0.2.25");
    String duplicate = socketMessage(protocol, "AMBIGUOUS-PEER");
    assertThat(postSocket(protocol, duplicate, "192.0.2.25", null)).isNotEqualTo(200);
    // Direct HTTP lookup currently reports a non-unique source as unregistered.
    assertRetainedFailure(duplicate, FailureReason.UNREGISTERED_SOURCE);
    outbox.dispatcher.dispatchDue();
    assertThat(received).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7" })
  void retainedMismatchOnlyDeliversAfterASavedCompatibleConnectionIsActivated(String actualProtocol) throws Exception {
    String wrongProtocol = actualProtocol.equals("ASTM") ? "HL7" : "ASTM";
    publishHttpProfile(wrongProtocol);
    ObjectNode wrong = activate("oe-wrong-protocol", "192.0.2.25");
    String raw = socketMessage(actualProtocol, "CORRECTED-CONNECTION");
    assertThat(postSocket(actualProtocol, raw, "192.0.2.25", null)).isNotEqualTo(200);
    var receipt = outbox.store.list(OutboxQuery.inState(OutboxState.DMQ, 10)).get(0);
    reopen();
    outbox.store.requestRetry(receipt.id(), "operator-before-correction", Instant.now());
    outbox.dispatcher.dispatchDue();
    assertRetainedFailure(raw, FailureReason.CONNECTION_TRANSPORT_MISMATCH);
    assertThat(received).isEmpty();
    connections.applyRuntimeCommand(command(wrong, "DEACTIVATE"));
    publishHttpProfile(actualProtocol);
    ObjectNode corrected = activate("oe-corrected-protocol", "192.0.2.25");
    Instant requested = Instant.now();
    outbox.store.requestRetry(receipt.id(), "operator-after-correction", requested);
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(1);
    assertSocketBundle(received.get(0), corrected, "CORRECTED-CONNECTION");
    var delivered = outbox.store.list(OutboxQuery.inState(OutboxState.DELIVERED, 10)).get(0);
    assertThat(delivered.connectionId()).isEqualTo(corrected.path("connectionId").asText());
    assertThat(delivered.retryRequestedBy()).isEqualTo("operator-after-correction");
    assertThat(delivered.retryRequestedAt()).isEqualTo(requested);
    assertThat(outbox.store.rawPayload(delivered.id())).contains(raw);
    assertThat(postSocket(actualProtocol, raw, "192.0.2.25", null)).isEqualTo(200);
    outbox.dispatcher.dispatchDue();
    assertThat(received).hasSize(1);
  }

  @Test
  void sameProtocolOnWrongTransportStaysHeldAfterOperatorRetry() throws Exception {
    publishHttpProfile("HL7");
    activate("oe-http-only", "192.0.2.25");
    String raw = socketMessage("HL7", "WRONG-TRANSPORT");
    var envelope = org.itech.ahb.normalizer.MessageEnvelope.builder()
      .protocol(org.itech.ahb.model.Protocol.HL7)
      .transport(org.itech.ahb.model.Transport.MLLP)
      .sourceId("192.0.2.25")
      .sourcePort(19000)
      .rawMessage(raw)
      .receivedAt(Instant.now())
      .build();
    assertThat(
      outbox.normalizer(new org.itech.ahb.normalizer.AnalyzerIdentifier(registry), registry).process(envelope)
    ).isFalse();
    assertRetainedFailure(raw, FailureReason.CONNECTION_TRANSPORT_MISMATCH);
    var held = outbox.store.list(OutboxQuery.inState(OutboxState.DMQ, 10)).get(0);
    reopen();
    outbox.store.requestRetry(held.id(), "operator", Instant.now());
    outbox.dispatcher.dispatchDue();
    assertRetainedFailure(raw, FailureReason.CONNECTION_TRANSPORT_MISMATCH);
    assertThat(received).isEmpty();
  }

  private void publishHttpProfile(String protocol) {
    profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(new ClassPathResource("analyzer-profiles/genexpert-astm-v5.json")),
      mapper,
      Clock.systemUTC()
    );
    var draft = profiles.duplicateDraft("genexpert-astm", 5, "Synthetic HTTP " + protocol, "test-author");
    ObjectNode candidate = draft.profile();
    candidate.putArray("transport").add("HTTP");
    candidate.putObject("transport_config").putObject("HTTP");
    candidate.withObject("configDefaults").put("transport", "HTTP").remove("port");
    if (protocol.equals("HL7")) {
      candidate.putObject("protocol").put("name", "HL7").put("version", "2.5.1");
      candidate.withObject("configDefaults").remove("extractionOverrides");
      // Synthetic fixture rule only; this is not evidence about a Madagascar instrument's QC behavior.
      candidate
        .putObject("controlResultRecognition")
        .put("mode", "RULES")
        .putObject("rules")
        .putObject("synthetic-control")
        .put("ruleType", "FIELD_EQUALS")
        .put("targetField", "OBX.3.2")
        .put("operand", "CONTROL");
    }
    var fields = candidate.putArray("connectionFields");
    fields
      .addObject()
      .put("key", "transport")
      .put("labelKey", "analyzer.connection.field.transport")
      .put("inputKind", "SELECT")
      .put("required", true)
      .putArray("choices")
      .addObject()
      .put("value", "HTTP")
      .put("labelKey", "analyzer.transport.http");
    fields
      .addObject()
      .put("key", "host")
      .put("labelKey", "analyzer.connection.field.host")
      .put("inputKind", "TEXT")
      .put("required", true)
      .putArray("choices");
    assertThat(profiles.updateDraft(draft.draftId(), candidate, "test-author").validationIssues()).isEmpty();
    profile = profiles.publishDraft(draft.draftId(), "test-publisher").profile();
    reopen();
  }

  private String socketMessage(String protocol, String accession) {
    // The message sender deliberately differs from the saved analyzer: HTTP ownership is peer-bound.
    return protocol.equals("HL7")
      ? "MSH|^~\\&|OTHER-SENDER|LAB|OE|LAB|20260924120000||ORU^R01|" +
      accession +
      "|P|2.5.1\r" +
      "PID|1||PATIENT\rOBR|1||" +
      accession +
      "|PANEL\rOBX|1|ST|MTB-RIF^PATIENT||NOT DETECTED|unit\r"
      : "H|@^\\|GXM-04567890||OTHER-SENDER^GeneXpert^6.2|||||geneexpert||P|1394-97|20260414120000\r" +
      "P|1\rO|1|" +
      accession +
      "||^^^MTBRif\r" +
      "R|1|^MTBRif^^MTB-RIF^Xpert MTB/RIF Ultra^3^MTB-RIF^|NOT DETECTED^||||||20260414120000\rL|1\r";
  }

  private int postSocket(String protocol, String raw, String peer, String forwarded) throws Exception {
    var request = post("/input")
      .contentType(protocol.equals("HL7") ? "application/hl7-v2" : "application/x-astm")
      .content(raw)
      .with(req -> {
        req.setRemoteAddr(peer);
        req.setRemotePort(19000);
        return req;
      });
    if (forwarded != null) request.header("X-Forwarded-For", forwarded);
    return input.perform(request).andReturn().getResponse().getStatus();
  }

  private void assertRetainedFailure(String raw, FailureReason reason) {
    assertThat(outbox.store.list(OutboxQuery.inState(OutboxState.DMQ, 10))).anySatisfy(entry -> {
      assertThat(entry.failureReason()).isEqualTo(reason);
      assertThat(outbox.store.rawPayload(entry.id())).contains(raw);
      assertThat(outbox.store.fhirPayload(entry.id())).isEmpty();
    });
  }

  private void assertSocketBundle(JsonNode bundle, ObjectNode connection, String accession) {
    var resources = java.util.stream.StreamSupport.stream(bundle.path("entry").spliterator(), false)
      .map(entry -> entry.path("resource"))
      .toList();
    assertThat(resources).anySatisfy(resource -> {
      assertThat(resource.path("resourceType").asText()).isEqualTo("Device");
      assertThat(resource.path("identifier")).anySatisfy(identifier -> {
        assertThat(identifier.path("system").asText()).isEqualTo(
          "https://openelis-global.org/fhir/analyzer-connection-id"
        );
        assertThat(identifier.path("value").asText()).isEqualTo(connection.path("connectionId").asText());
      });
      assertThat(resource.path("extension")).anySatisfy(extension -> {
        assertThat(extension.path("url").asText()).isEqualTo(
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-revision"
        );
        assertThat(extension.path("valueInteger").asInt()).isEqualTo(
          connection.path("profileRef").path("revision").asInt()
        );
      });
      assertThat(resource.path("extension")).anySatisfy(extension -> {
        assertThat(extension.path("url").asText()).isEqualTo(
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-id"
        );
        assertThat(extension.path("valueString").asText()).isEqualTo(profile.path("profileMeta").path("id").asText());
      });
    });
    assertThat(resources).anySatisfy(resource -> {
      assertThat(resource.path("resourceType").asText()).isEqualTo("Specimen");
      assertThat(resource.path("identifier")).anySatisfy(
        identifier -> assertThat(identifier.path("value").asText()).isEqualTo(accession)
      );
    });
    assertThat(
      resources.stream().filter(resource -> "Observation".equals(resource.path("resourceType").asText())).toList()
    ).hasSize(1);
    assertThat(resources).anySatisfy(resource -> {
      assertThat(resource.path("resourceType").asText()).isEqualTo("Observation");
      assertThat(resource.path("code").path("coding")).anySatisfy(
        coding -> assertThat(coding.path("code").asText()).isEqualTo("MTB-RIF")
      );
      assertThat(resource.path("valueString").asText()).isEqualTo("NOT DETECTED");
      assertThat(resource.path("extension")).anySatisfy(extension -> {
        assertThat(extension.path("url").asText()).isEqualTo(
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-classification"
        );
        assertThat(extension.path("valueCode").asText()).isEqualTo("PATIENT");
      });
    });
  }

  private ObjectNode create(String analyzerId, String host) {
    ObjectNode request = mapper.createObjectNode();
    request
      .put("schemaVersion", "1.0")
      .put("requestId", "create-" + analyzerId)
      .put("clientAnalyzerId", analyzerId)
      .put("displayName", analyzerId);
    request
      .putObject("profileRef")
      .put("profileId", profile.path("profileMeta").path("id").asText())
      .put("revision", 1)
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.putObject("values").put("transport", "HTTP").put("host", host);
    return connections.create(request);
  }

  private ObjectNode activate(String analyzerId, String host) {
    ObjectNode connection = create(analyzerId, host);
    assertThat(connection.path("readiness").path("ready").asBoolean()).isTrue();
    ObjectNode ack = connections.applyRuntimeCommand(command(connection, "ACTIVATE"));
    assertThat(ack.path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    return connection;
  }

  private ObjectNode command(ObjectNode connection, String action) {
    return mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("commandId", UUID.randomUUID().toString())
      .put("connectionId", connection.path("connectionId").asText())
      .put("action", action)
      .put("expectedConfigRevision", 1);
  }

  private int send(String remoteAddress, String forwarded) {
    String csv =
      "Export metadata\nSample ID;TargetName;Calc. Conc.;Interpretation;Type\n" +
      "SAMPLE-1;VIH-1;17.5;;Patient\nC+CONTROL;VIH-1;;Detected;Positive\n";
    var request = post("/input")
      .contentType("text/csv; charset=UTF-8")
      .content(csv)
      .with(servletRequest -> {
        servletRequest.setRemoteAddr(remoteAddress);
        servletRequest.setRemotePort(19000);
        return servletRequest;
      });
    if (forwarded != null) request.header("X-Forwarded-For", forwarded);
    try {
      int status = input.perform(request).andReturn().getResponse().getStatus();
      // Receipt and delivery are separate now: the request returns once the result is durably held,
      // and the dispatcher delivers it. Drive the dispatcher so the assertions see the delivery.
      outbox.dispatcher.dispatchDue();
      return status;
    } catch (Exception exception) {
      throw new AssertionError("HTTP CSV request failed", exception);
    }
  }
}
