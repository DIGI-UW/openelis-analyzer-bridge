package org.itech.ahb.connection;

import org.itech.ahb.outbox.OutboxTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.controller.AnalyzerInputController;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Uses the durable catalogs, actual runtime/normalizer/parser, and an HTTP receiver. */
class HttpCsvConnectionTest {

  private OutboxTestSupport outbox;

  @TempDir
  Path directory;

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<ObjectNode> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger rejectDeliveryNumber = new AtomicInteger();
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
      exchange.sendResponseHeaders(received.size() == rejectDeliveryNumber.get() ? 503 : 200, -1);
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
    outbox = OutboxTestSupport.createTemp(config, registry);
    input = MockMvcBuilders.standaloneSetup(
      new AnalyzerInputController(outbox.normalizer(new AnalyzerIdentifier(registry), registry))
    ).build();
  }

  @AfterEach
  void stop() {
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
    assertThat(
      received.subList(2, 4).stream().map(bundle -> bundle.path("identifier").path("value").asText())
    ).containsExactlyInAnyOrderElementsOf(firstIds);
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
    assertThat(received).hasSize(4);

    activate("oe-ipv6-duplicate", observed);
    assertThat(send(observed, null)).isNotEqualTo(200);
    assertThat(received).hasSize(4);
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
