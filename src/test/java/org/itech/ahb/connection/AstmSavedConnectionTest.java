package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.order.OutboundAstmClient;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/** Published profile -> saved activation -> real ASTM frames -> durable outbox -> HTTP receiver.
 * The receiver checks the normalized contract, not OE2 clinical processing or frame ACK crash safety.
 */
class AstmSavedConnectionTest {

  @TempDir
  Path directory;

  private final ObjectMapper mapper = new ObjectMapper();
  private final LinkedBlockingQueue<JsonNode> deliveries = new LinkedBlockingQueue<>();
  private final ASTMLIS1AListenServerConfigurationProperties inbound =
    new ASTMLIS1AListenServerConfigurationProperties();
  private HttpServer receiver;
  private OutboxTestSupport outbox;
  private ManagedAstmConnectionListeners listeners;
  private AnalyzerConnectionCatalog catalog;
  private AnalyzerProfileCatalog profiles;
  private ObjectNode profile;

  @BeforeEach
  void start() throws Exception {
    try (ServerSocket available = new ServerSocket(0)) {
      inbound.setPort(available.getLocalPort());
    }
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/analyzer/fhir", exchange -> {
      deliveries.add(mapper.readTree(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("OK".getBytes(StandardCharsets.UTF_8));
      exchange.close();
    });
    receiver.start();
    boot();
  }

  @AfterEach
  void close() {
    try {
      if (listeners != null) listeners.stopAll();
    } finally {
      if (outbox != null) outbox.close();
      if (receiver != null) receiver.stop(0);
    }
  }

  private void boot() throws Exception {
    if (listeners != null) listeners.stopAll();
    if (outbox != null) outbox.close();
    profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(new ClassPathResource("analyzer-profiles/genexpert-astm-v5.json")),
      mapper,
      Clock.systemUTC()
    );
    profile = profiles.require("genexpert-astm", 5).profile();
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    HTTPForwardServerConfigurationProperties forwarding = new HTTPForwardServerConfigurationProperties();
    forwarding.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    outbox = OutboxTestSupport.create(directory.resolve("outbox"), forwarding, registry).startDispatcher();
    listeners = new ManagedAstmConnectionListeners(
      outbox.normalizer(new AnalyzerIdentifier(registry), registry),
      new DefaultASTMInterpreterFactory()
    );
    catalog = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      mapper,
      Clock.systemUTC(),
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        registry,
        null,
        listeners,
        null,
        null,
        new AnalyzerListenerPorts(inbound, new ASTME138195ListenServerConfigurationProperties(), new MLLPConfig())
      )
    );
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1200 })
  void publishedGeneXpertReceivesOnConfiguredListenerWithMissingOrOldPortAcrossRestart(int oldPort) throws Exception {
    ObjectNode original = profile.deepCopy();
    ObjectNode request = mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", UUID.randomUUID().toString())
      .put("clientAnalyzerId", "oe-gx")
      .put("displayName", "GeneXpert bench");
    request
      .putObject("profileRef")
      .put("profileId", "genexpert-astm")
      .put("revision", 5)
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.putObject("values").put("host", "127.0.0.1").put("senderId", "GX-BENCH");
    if (oldPort > 0) request.withObject("values").put("port", oldPort);
    ObjectNode created = catalog.create(request);
    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
    String id = created.path("connectionId").asText();
    ObjectNode command = mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("commandId", UUID.randomUUID().toString())
      .put("connectionId", id)
      .put("action", "ACTIVATE")
      .put("expectedConfigRevision", 1);
    assertThat(catalog.applyRuntimeCommand(command).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertDelivery(id, "GX-BEFORE");
    boot();
    assertThat(catalog.require(id).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(catalog.require(id).path("profileRef")).isEqualTo(created.path("profileRef"));
    assertDelivery(id, "GX-AFTER");
    assertThat(profiles.require("genexpert-astm", 5).profile()).isEqualTo(original);
  }

  private void assertDelivery(String connectionId, String accession) throws Exception {
    List<String> records = List.of(
      "H|@^\\|GXM-04567890||GX-BENCH^GeneXpert^6.2|||||geneexpert||P|1394-97|20260414120000\r",
      "P|1\r",
      "O|1|" + accession + "||^^^MTBRif\r",
      "R|1|^MTBRif^^MTB-RIF^Xpert MTB/RIF Ultra^3^MTB-RIF^|NOT DETECTED^||||||20260414120000\r",
      "L|1\r"
    );
    assertThat(new OutboundAstmClient().send("127.0.0.1", inbound.getPort(), records, 3000)).isTrue();
    JsonNode bundle = deliveries.poll(5, TimeUnit.SECONDS);
    assertThat(bundle).isNotNull();
    var resources = java.util.stream.StreamSupport.stream(bundle.path("entry").spliterator(), false)
      .map(entry -> entry.path("resource"))
      .toList();
    JsonNode device = resources
      .stream()
      .filter(r -> "Device".equals(r.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertThat(device.path("identifier")).anySatisfy(identifier -> {
      assertThat(identifier.path("system").asText()).isEqualTo(
        "https://openelis-global.org/fhir/analyzer-connection-id"
      );
      assertThat(identifier.path("value").asText()).isEqualTo(connectionId);
    });
    assertThat(device.path("identifier")).anySatisfy(identifier -> {
      assertThat(identifier.path("system").asText()).isEqualTo("https://openelis-global.org/fhir/analyzer-id");
      assertThat(identifier.path("value").asText()).isEqualTo("oe-gx");
    });
    JsonNode observation = resources
      .stream()
      .filter(r -> "Observation".equals(r.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertThat(observation.path("code").path("coding")).anySatisfy(
      coding -> assertThat(coding.path("code").asText()).isEqualTo("MTB-RIF")
    );
    assertThat(observation.toString()).contains("NOT DETECTED");
    assertThat(bundle.toString()).contains(accession);
  }
}
