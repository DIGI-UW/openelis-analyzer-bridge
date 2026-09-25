package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.controller.OutboundOrderController;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.order.OutboundAstmClient;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real saved catalog -> owned MLLP socket -> normalizer -> HTTP delivery. */
class Hl7SavedConnectionTest {

  private final java.util.concurrent.atomic.AtomicInteger messageControlId =
    new java.util.concurrent.atomic.AtomicInteger();

  private OutboxTestSupport outbox;

  @TempDir
  Path directory;

  private final ObjectMapper mapper = new ObjectMapper();
  private final LinkedBlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
  private HttpServer receiver;
  private ObjectNode profile;
  private ManagedHl7ConnectionListeners listeners;
  private AnalyzerRuntimeRegistry registry;
  private AnalyzerConnectionCatalog catalog;
  private MLLPConfig listenerConfig;
  private int sharedPort;

  @BeforeEach
  void setUp() throws Exception {
    sharedPort = freePort();
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/", exchange -> {
      deliveries.add(
        new Delivery(
          exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst("Content-Type"),
          exchange
            .getRequestHeaders()
            .keySet()
            .stream()
            .anyMatch(
              key ->
                key.toLowerCase(java.util.Locale.ROOT).startsWith("x-source-") || key.equalsIgnoreCase("X-Analyzer-Id")
            ),
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        )
      );
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("OK".getBytes(StandardCharsets.UTF_8));
      exchange.close();
    });
    receiver.start();
    profile = (ObjectNode) mapper.readTree(
      Files.readString(Path.of("contracts/analyzer/v1/fixtures/analyzer-profile-astm.json"))
    );
    profile.withObject("profileMeta").put("id", "saved-hl7-fixture").put("displayName", "Saved HL7 fixture");
    profile.putObject("protocol").put("name", "HL7").put("version", "2.5.1");
    profile.withObject("configDefaults").remove("extractionOverrides");
    ObjectNode recognition = profile.putObject("controlResultRecognition");
    recognition.put("mode", "RULES");
    recognition
      .putObject("rules")
      .putObject("control-label")
      .put("ruleType", "FIELD_EQUALS")
      .put("targetField", "OBX.3.2")
      .put("operand", "CONTROL");
    profile
      .withArray("connectionFields")
      .forEach(field -> {
        if ("port".equals(field.path("key").asText())) ((ObjectNode) field).put("required", false);
      });
    profile
      .withArray("connectionFields")
      .addObject()
      .put("key", "senderId")
      .put("labelKey", "analyzer.connection.field.senderId")
      .put("inputKind", "TEXT")
      .put("required", false)
      .putArray("choices");
    publishProfile();
    boot(true);
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

  @ParameterizedTest
  @ValueSource(strings = { "TCP/IP", "MLLP" })
  void samePeerConnectionsKeepTheirIdentityOnOneSharedListenerAcrossRestartAndDeactivation(String transport)
    throws Exception {
    selectTransport(transport);
    profile.withObject("configDefaults").put("dataFlow", "RESULTS_ONLY");
    publishProfile();
    boot(true);
    String first = create("oe-first");
    activate(first);
    var probe = new AnalyzerConnectionProbe(
      mapper,
      Clock.systemUTC(),
      new org.itech.ahb.connectivity.DefaultConnectionProbeExecutor(),
      (protocol, port) -> false,
      new AnalyzerListenerPorts(
        new org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties(),
        new org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties(),
        listenerConfig
      )
    );
    var probeResult = catalog.probe(
      mapper
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("requestId", UUID.randomUUID().toString())
        .put("connectionId", first)
        .put("expectedConfigRevision", 1),
      probe
    );
    assertThat(probeResult.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(probeResult.path("checks").get(0).path("details").path("port").asInt()).isEqualTo(sharedPort);
    String second = create("oe-second");
    ObjectNode activation = command(second, "ACTIVATE");
    assertThat(catalog.applyRuntimeCommand(activation).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(catalog.applyRuntimeCommand(activation).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(listeners.runningConnections()).hasSize(2);
    assertDelivery(sharedPort, first, "oe-first");
    assertDelivery(sharedPort, second, "oe-second");
    listeners.stopAll();
    assertClosed(sharedPort);
    boot(true); // Fresh catalogs, registry and listeners; only the files survive.
    assertThat(catalog.require(first).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(listeners.runningConnections()).containsEntry(first, true).containsEntry(second, true);
    assertDelivery(sharedPort, first, "oe-first");
    assertDelivery(sharedPort, second, "oe-second");
    catalog.applyRuntimeCommand(command(first, "DEACTIVATE"));
    assertThat(registry.findAnalyzerId("connection:" + first)).isEmpty();
    sendFixture(sharedPort, "oe-first");
    assertThat(outbox.store.list(org.itech.ahb.outbox.OutboxQuery.all(100))).anySatisfy(entry -> {
      assertThat(entry.failureReason()).isEqualTo(org.itech.ahb.outbox.FailureReason.UNREGISTERED_SOURCE);
      assertThat(entry.protocolHint()).contains("oe-first");
      assertThat(entry.rawByteLength()).isPositive();
      assertThat(entry.fhirByteLength()).isZero();
    });
    // Any incorrectly forwarded first message would be consumed here and fail the identity assertions.
    assertDelivery(sharedPort, second, "oe-second");
    assertThat(catalog.applyRuntimeCommand(command(first, "DEACTIVATE")).path("actualRuntimeState").asText()).isEqualTo(
      "INACTIVE"
    );
    catalog.applyRuntimeCommand(command(second, "DEACTIVATE"));
    assertClosed(sharedPort);
  }

  @Test
  void historicalApplicationFacilityHintRetriesFromRetainedRawMessageAfterRestart() throws Exception {
    String id = create("oe-first");
    activate(id);
    outbox.dispatcher.stop();
    String raw =
      "MSH|^~\\&|oe-first|OTHER|OE|LAB|20260909120000||ORU^R01|OLD-HINT|P|2.5.1\r" +
      "PID|1||PATIENT\rOBR|1||OLD-ACCESSION|PANEL\rOBX|1|NM|T1^PATIENT||1|unit\r";
    var receipt = outbox.store.receive(
      new org.itech.ahb.outbox.ReceivedMessage(
        "127.0.0.1",
        55000,
        org.itech.ahb.model.Protocol.HL7,
        org.itech.ahb.model.Transport.MLLP,
        "oe-first-OTHER",
        raw,
        "UTF-8",
        java.time.Instant.now(),
        sharedPort
      )
    );
    outbox.store.markDeadLettered(
      receipt.id(),
      org.itech.ahb.outbox.FailureReason.UNREGISTERED_SOURCE,
      "Historical combined sender hint"
    );
    listeners.stopAll();
    boot(true);
    assertThat(outbox.store.rawPayload(receipt.id())).contains(raw);
    outbox.store.requestRetry(receipt.id(), "operator", java.time.Instant.now());
    outbox.dispatcher.dispatchDue();
    Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
    assertThat(delivery).isNotNull();
    JsonNode bundle = mapper.readTree(delivery.body());
    JsonNode device = java.util.stream.StreamSupport.stream(bundle.path("entry").spliterator(), false)
      .map(entry -> entry.path("resource"))
      .filter(resource -> "Device".equals(resource.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertThat(device.path("identifier")).anySatisfy(identifier -> {
      assertThat(identifier.path("system").asText()).isEqualTo(
        "https://openelis-global.org/fhir/analyzer-connection-id"
      );
      assertThat(identifier.path("value").asText()).isEqualTo(id);
    });
    assertThat(delivery.body()).contains("OLD-ACCESSION");
    var rendered = outbox.store
      .list(org.itech.ahb.outbox.OutboxQuery.all(100))
      .stream()
      .filter(entry -> "OLD-ACCESSION".equals(entry.accession()))
      .findFirst()
      .orElseThrow();
    assertThat(rendered.protocolHint()).isEqualTo("oe-first-OTHER");
    assertThat(outbox.store.rawPayload(rendered.id())).contains(raw);
  }

  @Test
  void occupiedDeploymentPortFailsActivationWithoutAuthorizingAConnectionAndCanBeRetried() throws Exception {
    String id;
    try (ServerSocket occupied = new ServerSocket(0)) {
      sharedPort = occupied.getLocalPort();
      listenerConfig.setPort(sharedPort);
      id = create("oe-occupied");
      String connectionId = id;
      assertThatThrownBy(() -> activate(connectionId)).isInstanceOf(AnalyzerConnectionException.class);
      assertThat(catalog.require(id).path("actualRuntimeState").asText()).isEqualTo("INACTIVE");
      assertThat(registry.findAnalyzerId("connection:" + id)).isEmpty();
      assertThat(listeners.runningConnections()).isEmpty();
      assertThat(occupied.isClosed()).isFalse();
    }
    activate(id);
    assertDelivery(sharedPort, id, "oe-occupied");
  }

  @Test
  void failedSenderReplacementAndRestartKeepTheLastActivatedIdentity() throws Exception {
    String first = create("oe-first");
    String second = create("oe-second");
    activate(first);
    activate(second);
    ObjectNode saved = catalog.require(first);
    ObjectNode update = mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", UUID.randomUUID().toString())
      .put("connectionId", first)
      .put("expectedConfigRevision", 1)
      .put("displayName", "oe-first");
    update.set("profileRef", saved.path("profileRef").deepCopy());
    update.putObject("values").put("senderId", "oe-second");
    catalog.update(update);
    ObjectNode replacement = command(first, "ACTIVATE").put("expectedConfigRevision", 2);
    assertThatThrownBy(() -> catalog.applyRuntimeCommand(replacement)).isInstanceOf(AnalyzerConnectionException.class);
    assertDelivery(sharedPort, first, "oe-first");
    listeners.stopAll();
    boot(true);
    assertThat(catalog.require(first).path("configRevision").asInt()).isEqualTo(2);
    assertThat(catalog.require(first).path("activeRuntimeRef").path("configRevision").asInt()).isEqualTo(1);
    assertDelivery(sharedPort, first, "oe-first");
    assertDelivery(sharedPort, second, "oe-second");
  }

  @Test
  void disabledRuntimeDoesNotAuthorizeOrOpenSavedConnections() throws Exception {
    listeners.stopAll();
    boot(false);
    String id = create("oe-disabled");
    assertThatThrownBy(() -> activate(id))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("disabled");
    assertThat(registry.findAnalyzerId("connection:" + id)).isEmpty();
    assertClosed(sharedPort);
  }

  /** Real saved publication and outbound wire traffic. This does not claim durable outbound-order queuing. */
  @ParameterizedTest
  @ValueSource(strings = { "TCP/IP", "MLLP" })
  void clientDispatchesOrdersWithoutAnInboundListenerBeforeAndAfterRestart(String transport) throws Exception {
    try (ServerSocket peer = new ServerSocket(0)) {
      peer.setSoTimeout(5000);
      selectTransport(transport);
      profile.withObject("configDefaults").put("connectionRole", "CLIENT").put("dataFlow", "TWO_WAY");
      profile.withObject("transport_config").withObject(transport).put("default_port", peer.getLocalPort());
      publishProfile();
      boot(true);
      String id = create("oe-outbound");
      activate(id);
      assertClosed(sharedPort);
      assertThat(listeners.runningConnections()).isEmpty();
      assertOrder(peer, id, "BEFORE-RESTART", "AA", true);
      boot(true);
      assertClosed(sharedPort);
      assertThat(listeners.runningConnections()).isEmpty();
      assertOrder(peer, id, "AFTER-RESTART", "AA", true);
      assertOrder(peer, id, "ANALYZER-REJECTED", "AE", false);
      catalog.applyRuntimeCommand(command(id, "DEACTIVATE"));
      var request = orderRequest(id, "AFTER-DEACTIVATE");
      assertThat(orderController().sendOrder(request).getStatusCode().value()).isEqualTo(422);
      assertThat(registry.findAnalyzerEntryByConnectionId(id)).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource(
    {
      "TCP/IP, RESULTS_ONLY",
      "MLLP, RESULTS_ONLY",
      "TCP/IP, NO_ORDERS",
      "MLLP, NO_ORDERS",
      "TCP/IP, NO_LIS",
      "MLLP, NO_LIS"
    }
  )
  void unsupportedClientCannotBecomeActiveWithoutAnExecutableTransport(String transport, String restriction)
    throws Exception {
    selectTransport(transport);
    profile.withObject("configDefaults").put("connectionRole", "CLIENT").put("dataFlow", "TWO_WAY");
    if ("RESULTS_ONLY".equals(restriction)) profile.withObject("configDefaults").put("dataFlow", "RESULTS_ONLY");
    if ("NO_ORDERS".equals(restriction)) profile.withObject("capabilities").put("outboundOrders", false);
    if ("NO_LIS".equals(restriction)) profile.withObject("communication").put("supports_lis_initiated", false);
    publishProfile();
    boot(true);
    String id = create("oe-unsupported-client");
    assertThatThrownBy(() -> activate(id))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("outbound orders");
    assertThat(catalog.require(id).path("actualRuntimeState").asText()).isEqualTo("INACTIVE");
    assertThat(registry.findAnalyzerEntryByConnectionId(id)).isEmpty();
    assertThat(listeners.runningConnections()).isEmpty();
    assertClosed(sharedPort);
  }

  @ParameterizedTest
  @ValueSource(strings = { "HTTP", "MLLP" })
  void followingSpecimenPolicyStaysPinnedAcrossRestartAndNewPublication(String transport) throws Exception {
    selectTransport(transport);
    profile
      .withObject("configDefaults")
      .put("dataFlow", "RESULTS_ONLY")
      .putObject("extractionOverrides")
      .put("specimenPosition", "FOLLOWING_OBX");
    profile
      .withObject("controlResultRecognition")
      .withObject("rules")
      .withObject("control-label")
      .put("targetField", "SPM.11")
      .put("operand", "Q");
    publishProfile();
    listeners.stopAll();
    boot(true);
    String id = create("specimen-sender");
    activate(id);
    ObjectNode pinned = catalog.require(id).withObject("profileRef").deepCopy();
    for (int phase = 0; phase < 2; phase++) {
      if (phase == 1) {
        var profiles = new AnalyzerProfileCatalog(directory.resolve("profiles"), List.of(), mapper, Clock.systemUTC());
        var draft = profiles.updateSharedDraft(pinned.path("profileId").asText(), 1, "author");
        var candidate = draft.profile();
        candidate.withObject("configDefaults").withObject("extractionOverrides").put("specimenPosition", "PRECEDING");
        assertThat(profiles.updateDraft(draft.draftId(), candidate, "author").validationIssues()).isEmpty();
        assertThat(
          profiles.publishDraft(draft.draftId(), "publisher").profile().path("catalog").path("revision").asInt()
        ).isEqualTo(2);
        listeners.stopAll();
        boot(true);
        assertThat(catalog.require(id).path("profileRef")).isEqualTo(pinned);
      }
      String accession = "SPM-" + phase;
      String message =
        "MSH|^~\\&|specimen-sender|LAB|OE|LAB|20260924120000||ORU^R01|SPM-" +
        phase +
        "|P|2.5.1\r" +
        "PID|1||PATIENT\rOBR|1||" +
        accession +
        "|PANEL\r" +
        "OBX|1|NM|T1||1|unit\rNTE|1||synthetic\rSPM|1||||||||||Q\r" +
        "OBX|2|NM|T2||2|unit\rSPM|2||||||||||P\r";
      if (transport.equals("HTTP")) {
        var input = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
          new org.itech.ahb.controller.AnalyzerInputController(
            outbox.normalizer(new AnalyzerIdentifier(registry), registry)
          )
        ).build();
        assertThat(
          input
            .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/input")
                .contentType("application/hl7-v2")
                .content(message)
                .with(request -> {
                  request.setRemoteAddr("127.0.0.1");
                  return request;
                })
            )
            .andReturn()
            .getResponse()
            .getStatus()
        ).isEqualTo(200);
      } else {
        try (Socket socket = new Socket("127.0.0.1", sharedPort)) {
          socket.setSoTimeout(5000);
          socket.getOutputStream().write(("\u000b" + message + "\u001c\r").getBytes(StandardCharsets.UTF_8));
          StringBuilder ack = new StringBuilder();
          int next;
          while ((next = socket.getInputStream().read()) != -1 && next != 0x1c) ack.append((char) next);
          assertThat(ack.toString()).contains("MSA|AA|");
        }
      }
      Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
      assertThat(delivery).isNotNull();
      Bundle bundle = FhirContext.forR4Cached().newJsonParser().parseResource(Bundle.class, delivery.body());
      var resources = bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource).toList();
      Device device = resources
        .stream()
        .filter(Device.class::isInstance)
        .map(Device.class::cast)
        .findFirst()
        .orElseThrow();
      String root = "https://openelis-global.org/fhir/StructureDefinition/";
      assertThat(device.getIdentifier()).anySatisfy(identifier -> {
        assertThat(identifier.getSystem()).isEqualTo("https://openelis-global.org/fhir/analyzer-connection-id");
        assertThat(identifier.getValue()).isEqualTo(id);
      });
      assertThat(device.getExtensionByUrl(root + "analyzer-profile-id").getValue().primitiveValue()).isEqualTo(
        pinned.path("profileId").asText()
      );
      assertThat(device.getExtensionByUrl(root + "analyzer-profile-revision").getValue().primitiveValue()).isEqualTo(
        "1"
      );
      var specimen = resources
        .stream()
        .filter(org.hl7.fhir.r4.model.Specimen.class::isInstance)
        .map(org.hl7.fhir.r4.model.Specimen.class::cast)
        .findFirst()
        .orElseThrow();
      assertThat(specimen.getIdentifier()).anySatisfy(
        identifier -> assertThat(identifier.getValue()).isEqualTo(accession)
      );
      var observations = resources.stream().filter(Observation.class::isInstance).map(Observation.class::cast).toList();
      assertThat(observations).hasSize(2);
      for (int i = 0; i < 2; i++) {
        var observation = observations.get(i);
        assertThat(observation.getValueQuantity().getValue().intValueExact()).isEqualTo(i + 1);
        assertThat(
          observation.getExtensionByUrl(root + "analyzer-result-classification").getValue().primitiveValue()
        ).isEqualTo(i == 0 ? "CONTROL" : "PATIENT");
        var recognition = observation.getExtensionByUrl(root + "analyzer-control-recognition");
        assertThat(recognition.getExtensionByUrl("recognitionFingerprint").getValue().primitiveValue()).isEqualTo(
          profile.path("catalog").path("recognitionFingerprint").asText()
        );
        var evidence = recognition.getExtensionByUrl("evaluation");
        assertThat(evidence.getExtensionByUrl("sourceField").getValue().primitiveValue()).isEqualTo("SPM.11");
        assertThat(evidence.getExtensionByUrl("rawValue").getValue().primitiveValue()).isEqualTo(i == 0 ? "Q" : "P");
        assertThat(evidence.getExtensionByUrl("matched").getValue().primitiveValue()).isEqualTo(
          Boolean.toString(i == 0)
        );
      }
    }
  }

  @Test
  void publicationRejectsUnknownSpecimenPolicy() {
    var profiles = new AnalyzerProfileCatalog(directory.resolve("profiles"), List.of(), mapper, Clock.systemUTC());
    var draft = profiles.updateSharedDraft(profile.path("profileMeta").path("id").asText(), 1, "author");
    var candidate = draft.profile();
    candidate.withObject("configDefaults").putObject("extractionOverrides").put("specimenPosition", "GUESS");
    assertThat(profiles.updateDraft(draft.draftId(), candidate, "author").validationIssues()).anySatisfy(
      issue -> assertThat(issue).contains("specimenPosition")
    );
    assertThatThrownBy(() -> profiles.publishDraft(draft.draftId(), "publisher")).isInstanceOf(
      org.itech.ahb.profile.ProfileCatalogException.class
    );
  }

  private void selectTransport(String transport) {
    profile.putArray("transport").add(transport);
    profile.putObject("transport_config").putObject(transport);
    profile.withObject("configDefaults").put("transport", transport);
    profile
      .withArray("connectionFields")
      .forEach(field -> {
        if ("transport".equals(field.path("key").asText())) {
          ((ObjectNode) field).putArray("choices")
            .addObject()
            .put("value", transport)
            .put("labelKey", "analyzer.connection.field.transport");
        }
      });
  }

  private void publishProfile() {
    var profiles = new AnalyzerProfileCatalog(directory.resolve("profiles"), List.of(), mapper, Clock.systemUTC());
    var draft = profiles.createDraft("Synthetic HL7 " + UUID.randomUUID(), "test-author");
    profile
      .withObject("profileMeta")
      .put("id", draft.profile().path("profileMeta").path("id").asText())
      .put("displayName", draft.profile().path("profileMeta").path("displayName").asText());
    profile.remove("catalog");
    assertThat(profiles.updateDraft(draft.draftId(), profile, "test-author").validationIssues()).isEmpty();
    profile = profiles.publishDraft(draft.draftId(), "test-publisher").profile();
  }

  private OutboundOrderController orderController() {
    return new OutboundOrderController(registry, new OutboundMllpClient(), new OutboundAstmClient());
  }

  private OutboundOrderController.OrderRequest orderRequest(String id, String accession) {
    var request = new OutboundOrderController.OrderRequest();
    request.connectionId = id;
    request.order = new OutboundOrderController.ClinicalOrder();
    request.order.accessionNumber = accession;
    request.order.patientId = "PATIENT-ORDER";
    request.order.loincCodes = List.of("85362-2");
    return request;
  }

  private void assertOrder(ServerSocket peer, String id, String accession, String ackCode, boolean success)
    throws Exception {
    CompletableFuture<String> wire = CompletableFuture.supplyAsync(() -> {
      try (Socket socket = peer.accept()) {
        socket.setSoTimeout(5000);
        var in = socket.getInputStream();
        if (in.read() != 0x0b) throw new AssertionError("Missing MLLP start marker");
        var body = new java.io.ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1 && value != 0x1c) body.write(value);
        if (value != 0x1c || in.read() != 0x0d) throw new AssertionError("Missing MLLP terminator");
        String message = body.toString(StandardCharsets.UTF_8);
        String controlId = message.split("\\r", -1)[0].split("\\|", -1)[9];
        String ack =
          "MSH|^~\\&|ANALYZER|LAB|OE|LAB|20260924120000||ACK|ACK-1|P|2.3.1\rMSA|" + ackCode + "|" + controlId + "\r";
        socket.getOutputStream().write(("\u000b" + ack + "\u001c\r").getBytes(StandardCharsets.UTF_8));
        return message;
      } catch (Exception error) {
        throw new java.util.concurrent.CompletionException(error);
      }
    });
    var response = orderController().sendOrder(orderRequest(id, accession));
    assertThat(response.getBody().get("dispatched")).isEqualTo(success);
    assertThat(response.getStatusCode().value()).isEqualTo(success ? 200 : 502);
    String message = wire.get(6, TimeUnit.SECONDS);
    var segments = java.util.Arrays.stream(message.split("\\r", -1)).map(line -> line.split("\\|", -1)).toList();
    assertThat(segments).anySatisfy(fields -> {
      assertThat(fields[0]).isEqualTo("PID");
      assertThat(fields[3]).isEqualTo("PATIENT-ORDER^^^HOSP");
    });
    assertThat(segments).anySatisfy(fields -> {
      assertThat(fields[0]).isEqualTo("OBR");
      assertThat(fields[2]).isEqualTo(accession);
      assertThat(fields[3]).isEqualTo(accession);
      assertThat(fields[4]).isEqualTo("^^^MTB-RIF^MTB-RIF");
    });
  }

  private void boot(boolean enabled) throws Exception {
    if (outbox != null) outbox.close();
    AnalyzerProfileCatalog profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(),
      mapper,
      Clock.systemUTC()
    );
    registry = new AnalyzerRuntimeRegistry();
    HTTPForwardServerConfigurationProperties forwarding = new HTTPForwardServerConfigurationProperties();
    forwarding.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    outbox = OutboxTestSupport.create(directory.resolve("outbox"), forwarding, registry).startDispatcher();
    MessageNormalizer normalizer = outbox.normalizer(new AnalyzerIdentifier(registry), registry);
    listenerConfig = new MLLPConfig();
    listenerConfig.setEnabled(enabled);
    listenerConfig.setPort(sharedPort);
    listeners = new ManagedHl7ConnectionListeners(listenerConfig, normalizer);
    catalog = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      mapper,
      Clock.systemUTC(),
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        registry,
        null,
        null,
        null,
        listeners,
        new AnalyzerListenerPorts(
          new org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties(),
          new org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties(),
          listenerConfig
        )
      )
    );
  }

  private String create(String analyzerId) {
    ObjectNode request = mapper.createObjectNode();
    request
      .put("schemaVersion", "1.0")
      .put("requestId", UUID.randomUUID().toString())
      .put("clientAnalyzerId", analyzerId)
      .put("displayName", analyzerId);
    request
      .putObject("profileRef")
      .put("profileId", profile.path("profileMeta").path("id").asText())
      .put("revision", 1)
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.putObject("values").put("host", "127.0.0.1").put("senderId", analyzerId);
    ObjectNode created = catalog.create(request);
    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
    return created.path("connectionId").asText();
  }

  private void activate(String id) {
    assertThat(catalog.applyRuntimeCommand(command(id, "ACTIVATE")).path("actualRuntimeState").asText()).isEqualTo(
      "ACTIVE"
    );
  }

  private ObjectNode command(String id, String action) {
    return mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("commandId", UUID.randomUUID().toString())
      .put("connectionId", id)
      .put("action", action)
      .put("expectedConfigRevision", 1);
  }

  private String sendFixture(int port, String analyzerId) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      // Same peer address and listener: the saved sender distinguishes these analyzers.
      // A distinct MSH-10 message control id per transmission, as real analyzer traffic carries.
      // Byte-identical repeats are retransmissions by definition, and the outbox now recognizes them
      // as such rather than delivering the same result to OpenELIS twice.
      String message =
        "MSH|^~\\&|" +
        analyzerId +
        "|OTHER|OE|LAB|20260909120000||ORU^R01|MSG-" +
        messageControlId.incrementAndGet() +
        "|P|2.5.1\r" +
        "PID|1||PATIENT\rOBR|1||ACCESSION|PANEL\r" +
        "OBX|1|NM|T1^PATIENT||1|unit\rOBX|2|NM|T2^CONTROL||2|unit\rOBX|3|NM|T3||3|unit\r";
      socket.getOutputStream().write(("\u000b" + message + "\u001c\r").getBytes(StandardCharsets.UTF_8));
      StringBuilder ack = new StringBuilder();
      int next;
      while ((next = socket.getInputStream().read()) != -1 && next != 0x1c) ack.append((char) next);
      return ack.toString();
    }
  }

  private void assertDelivery(int port, String id, String analyzerId) throws Exception {
    assertThat(sendFixture(port, analyzerId)).contains("MSA|AA|");
    Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
    assertThat(delivery).isNotNull();
    assertThat(delivery.path()).isEqualTo("/analyzer/fhir");
    assertThat(delivery.contentType()).startsWith("application/fhir+json");
    assertThat(delivery.hasLegacyHeaders()).isFalse();
    assertThat(
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(mapper.readTree(Path.of("contracts/analyzer/v1/normalized-fhir-bundle.schema.json").toFile()))
        .validate(mapper.readTree(delivery.body()))
    ).isEmpty();
    Bundle bundle = FhirContext.forR4Cached().newJsonParser().parseResource(Bundle.class, delivery.body());
    Device device = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Device.class::isInstance)
      .map(Device.class::cast)
      .findFirst()
      .orElseThrow();
    assertThat(device.getIdentifier())
      .anySatisfy(identifier -> {
        assertThat(identifier.getSystem()).isEqualTo("https://openelis-global.org/fhir/analyzer-connection-id");
        assertThat(identifier.getValue()).isEqualTo(id);
      })
      .anySatisfy(identifier -> {
        assertThat(identifier.getSystem()).isEqualTo("https://openelis-global.org/fhir/analyzer-id");
        assertThat(identifier.getValue()).isEqualTo(analyzerId);
      });
    String root = "https://openelis-global.org/fhir/StructureDefinition/";
    assertThat(device.getExtensionByUrl(root + "analyzer-profile-id").getValue().primitiveValue()).isEqualTo(
      profile.path("profileMeta").path("id").asText()
    );
    assertThat(device.getExtensionByUrl(root + "analyzer-profile-revision").getValue().primitiveValue()).isEqualTo("1");
    var observations = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .toList();
    assertThat(observations).hasSize(3);
    for (int index = 0; index < observations.size(); index++) {
      Observation observation = observations.get(index);
      assertThat(
        observation.getExtensionByUrl(root + "analyzer-result-classification").getValue().primitiveValue()
      ).isEqualTo(index == 1 ? "CONTROL" : "PATIENT");
      var recognition = observation.getExtensionByUrl(root + "analyzer-control-recognition");
      assertThat(recognition.getExtensionByUrl("recognitionFingerprint").getValue().primitiveValue()).isEqualTo(
        profile.path("catalog").path("recognitionFingerprint").asText()
      );
      var evidence = recognition.getExtensionByUrl("evaluation");
      assertThat(evidence.getExtensionByUrl("sourceField").getValue().primitiveValue()).isEqualTo("OBX.3.2");
      assertThat(evidence.getExtensionByUrl("matched").getValue().primitiveValue()).isEqualTo(
        Boolean.toString(index == 1)
      );
      assertThat(evidence.getExtensionByUrl("sourcePresent").getValue().primitiveValue()).isEqualTo(
        Boolean.toString(index < 2)
      );
      if (index < 2) {
        assertThat(evidence.getExtensionByUrl("rawValue").getValue().primitiveValue()).isEqualTo(
          index == 1 ? "CONTROL" : "PATIENT"
        );
      } else {
        assertThat(evidence.getExtensionByUrl("rawValue")).isNull();
      }
    }
  }

  private static void assertClosed(int port) {
    assertThatThrownBy(() -> {
      try (Socket socket = new Socket("127.0.0.1", port)) {}
    }).isInstanceOf(IOException.class);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private record Delivery(String path, String contentType, boolean hasLegacyHeaders, String body) {}
}
