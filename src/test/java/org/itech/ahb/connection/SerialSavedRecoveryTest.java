package org.itech.ahb.connection;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fazecast.jSerialComm.SerialPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.*;
import org.itech.ahb.controller.AnalyzerInputController;
import org.itech.ahb.health.SerialHealthIndicator;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.serial.SerialMessageHandler;
import org.itech.ahb.serial.SerialPortListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Real native pseudo-terminals, published profiles, saved restore and SQLite/HTTP delivery.
 * Requires Python 3 and Unix PTYs; fails rather than skipping if that test infrastructure is absent.
 * Does not prove electrical/USB hardware behavior or crash-safe ASTM frame acknowledgment. */
class SerialSavedRecoveryTest {

  @TempDir
  Path directory;

  final ObjectMapper json = new ObjectMapper();
  final LinkedBlockingQueue<JsonNode> deliveries = new LinkedBlockingQueue<>();
  SerialPortListener serial;
  OutboxTestSupport outbox;
  AnalyzerConnectionCatalog connections;
  AnalyzerProfileCatalog profiles;
  AnalyzerRuntimeRegistry registry;
  AnalyzerInputController http;
  HttpServer receiver;

  @Test
  @Timeout(60)
  void absentDeviceRestoresWithoutBlockingPeerAndReconnectsWithoutAnotherActivation() throws Exception {
    Pair pair = new Pair(directory);
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/analyzer/fhir", exchange -> {
      deliveries.add(json.readTree(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    receiver.start();
    try {
      pair.start();
      profiles = new AnalyzerProfileCatalog(
        directory.resolve("profiles"),
        List.of(new ClassPathResource("analyzer-profiles/genexpert-astm-v4.json")),
        json,
        Clock.systemUTC()
      );
      var draft = profiles.duplicateDraft("genexpert-astm", 4, "Serial recovery fixture", "test");
      ObjectNode authored = draft.profile().deepCopy();
      authored
        .withObject("transport_config")
        .withObject("RS-232")
        .put("reconnect_interval_ms", 100)
        .put("message_timeout_ms", 500);
      authored.withArray("transport").add("HTTP");
      for (JsonNode field : authored.path("connectionFields")) {
        if ("transport".equals(field.path("key").asText())) {
          ((ObjectNode) field).withArray("choices")
            .addObject()
            .put("value", "HTTP")
            .put("labelKey", "analyzer.transport.http");
        }
      }
      profiles.updateDraft(draft.draftId(), authored, "test");
      var serialProfile = profiles.publishDraft(draft.draftId(), "test").profile();
      boot();
      ObjectNode saved = create(serialProfile, "serial-owner", "RS-232", pair.bridge.toString());
      ObjectNode peer = create(serialProfile, "http-owner", "HTTP", "127.0.0.2");
      String id = saved.path("connectionId").asText();
      send(pair.peer, "BEFORE");
      assertDelivery(id, "BEFORE");
      pair.close();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertFalse(serial.getPortStatus(pair.bridge.toString()).isOpen());
          assertTrue(serial.getPortStatus(pair.bridge.toString()).isPendingReconnect());
        });
      boot();
      assertFalse(serial.getPortStatus(pair.bridge.toString()).isOpen());
      assertEquals("DOWN", new SerialHealthIndicator(serial).health().getStatus().getCode());
      assertEquals(saved.path("profileRef"), connections.require(id).path("profileRef"));
      sendHttp("PEER");
      assertDelivery(peer.path("connectionId").asText(), "PEER");
      boot(); // pending physical availability must survive repeated process restart
      pair.start();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertTrue(serial.getPortStatus(pair.bridge.toString()).isOpen()));
      send(pair.peer, "AFTER");
      assertDelivery(id, "AFTER");
      // A physical disconnect with the process still running also reconnects.
      pair.close();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertFalse(serial.getPortStatus(pair.bridge.toString()).isOpen()));
      pair.start();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertTrue(serial.getPortStatus(pair.bridge.toString()).isOpen()));
      send(pair.peer, "RECONNECTED");
      assertDelivery(id, "RECONNECTED");
      pair.close();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertFalse(serial.getPortStatus(pair.bridge.toString()).isOpen()));
      command(id, "DEACTIVATE");
      pair.start();
      await()
        .during(Duration.ofMillis(350))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertTrue(serial.getPortStatuses().isEmpty()));
      assertEquals("INACTIVE", connections.require(id).path("actualRuntimeState").asText());
    } finally {
      if (serial != null) serial.stopAll();
      if (outbox != null) outbox.close();
      pair.close();
      receiver.stop(0);
    }
  }

  void boot() {
    if (serial != null) serial.stopAll();
    if (outbox != null) outbox.close();
    registry = new AnalyzerRuntimeRegistry();
    var config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    outbox = OutboxTestSupport.create(directory.resolve("outbox"), config, registry).startDispatcher();
    var normalizer = outbox.normalizer(new AnalyzerIdentifier(registry), registry);
    serial = new SerialPortListener(new SerialMessageHandler(normalizer));
    connections = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      json,
      Clock.systemUTC(),
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(registry, null, null, serial)
    );
    http = new AnalyzerInputController(normalizer);
  }

  ObjectNode create(ObjectNode profile, String owner, String transport, String endpoint) {
    ObjectNode request = json
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", UUID.randomUUID().toString())
      .put("clientAnalyzerId", owner)
      .put("displayName", owner);
    request
      .putObject("profileRef")
      .put("profileId", profile.path("profileMeta").path("id").asText())
      .put("revision", profile.path("catalog").path("revision").asInt())
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request
      .putObject("values")
      .put("transport", transport)
      .put(transport.equals("RS-232") ? "serialPort" : "host", endpoint);
    ObjectNode saved = connections.create(request);
    assertTrue(saved.path("readiness").path("ready").asBoolean(), saved.toString());
    command(saved.path("connectionId").asText(), "ACTIVATE");
    return saved;
  }

  void command(String id, String action) {
    var ack = connections.applyRuntimeCommand(
      json
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("commandId", UUID.randomUUID().toString())
        .put("connectionId", id)
        .put("action", action)
        .put("expectedConfigRevision", 1)
    );
    assertEquals(
      action.equals("ACTIVATE") ? "ACTIVE" : "INACTIVE",
      ack.path("actualRuntimeState").asText(),
      ack.toString()
    );
  }

  String payload(String accession) {
    return (
      "H|@^\\|GXM-1||GX-BENCH^GeneXpert^6.2|||||geneexpert||P|1394-97|20260414120000\r" +
      "P|1\rO|1|" +
      accession +
      "||^^^MTBRif\r" +
      "R|1|^MTBRif^^MTB-RIF^Xpert MTB/RIF Ultra^3^MTB-RIF^|NOT DETECTED^||||||20260414120000\rL|1\r"
    );
  }

  void sendHttp(String accession) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.2");
    assertEquals(
      200,
      http.receiveAnalyzerMessage(payload(accession), "application/x-astm", null, null, request).getStatusCode().value()
    );
  }

  void send(Path peer, String accession) throws Exception {
    SerialPort port = SerialPort.getCommPort(peer.toString());
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 3000, 3000);
    assertTrue(port.openPort());
    try {
      assertEquals(1, port.writeBytes(new byte[] { 5 }, 1));
      ack(port);
      byte[] text = ("1" + payload(accession) + (char) 3).getBytes(StandardCharsets.ISO_8859_1);
      int sum = 0;
      for (byte b : text) sum = (sum + (b & 255)) & 255;
      var frame = new java.io.ByteArrayOutputStream();
      frame.write(2);
      frame.write(text);
      frame.write(String.format("%02X\r\n", sum).getBytes(StandardCharsets.US_ASCII));
      byte[] bytes = frame.toByteArray();
      assertEquals(bytes.length, port.writeBytes(bytes, bytes.length));
      ack(port);
      var held = outbox.store.list(
        new org.itech.ahb.outbox.OutboxQuery(
          org.itech.ahb.outbox.OutboxState.DMQ,
          null,
          org.itech.ahb.outbox.FailureReason.INCOMPLETE_TRANSMISSION,
          false,
          100,
          0
        )
      );
      assertEquals(1, held.size());
      org.junit.jupiter.api.Assertions.assertArrayEquals(
        bytes,
        outbox.store.astmFrames(held.getFirst().id()).orElseThrow()
      );
      assertEquals(1, port.writeBytes(new byte[] { 4 }, 1));
    } finally {
      port.closePort();
    }
  }

  void ack(SerialPort port) {
    byte[] reply = new byte[1];
    assertEquals(1, port.readBytes(reply, 1), "no ASTM acknowledgment");
    assertEquals(6, reply[0], "expected ASTM ACK");
  }

  void assertDelivery(String id, String accession) throws Exception {
    JsonNode bundle = deliveries.poll(5, TimeUnit.SECONDS);
    assertNotNull(bundle);
    List<JsonNode> resources = java.util.stream.StreamSupport.stream(bundle.path("entry").spliterator(), false)
      .map(item -> item.path("resource"))
      .toList();
    JsonNode device = resources
      .stream()
      .filter(r -> "Device".equals(r.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertTrue(
      java.util.stream.StreamSupport.stream(device.path("identifier").spliterator(), false).anyMatch(
        identifier ->
          "https://openelis-global.org/fhir/analyzer-connection-id".equals(identifier.path("system").asText()) &&
          id.equals(identifier.path("value").asText())
      )
    );
    JsonNode reference = connections.require(id).path("profileRef");
    assertTrue(
      java.util.stream.StreamSupport.stream(device.path("extension").spliterator(), false).anyMatch(
        extension ->
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-id".equals(
              extension.path("url").asText()
            ) &&
          reference.path("profileId").asText().equals(extension.path("valueString").asText())
      )
    );
    assertTrue(
      java.util.stream.StreamSupport.stream(device.path("extension").spliterator(), false).anyMatch(
        extension ->
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-revision".equals(
              extension.path("url").asText()
            ) &&
          reference.path("revision").asInt() == extension.path("valueInteger").asInt()
      )
    );
    JsonNode specimen = resources
      .stream()
      .filter(r -> "Specimen".equals(r.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertEquals(accession, specimen.path("identifier").get(0).path("value").asText());
    List<JsonNode> observations = resources
      .stream()
      .filter(r -> "Observation".equals(r.path("resourceType").asText()))
      .toList();
    assertEquals(1, observations.size());
    JsonNode observation = observations.get(0);
    assertEquals("NOT DETECTED", observation.path("valueString").asText());
    assertTrue(
      java.util.stream.StreamSupport.stream(observation.path("code").path("coding").spliterator(), false).anyMatch(
        coding ->
          "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code".equals(coding.path("system").asText()) &&
          "MTB-RIF".equals(coding.path("code").asText())
      )
    );
  }

  /** Two native PTYs relayed by Python's standard library; no preexisting devices or fixed paths. */
  static final class Pair implements AutoCloseable {

    final Path bridge, peer, log;
    Process process;

    Pair(Path directory) {
      bridge = directory.resolve("bridge-serial");
      peer = directory.resolve("analyzer-serial");
      log = directory.resolve("pty.log");
    }

    void start() throws Exception {
      process = new ProcessBuilder(
        "python3",
        "-u",
        "-c",
        """
        import os, pty, tty, select, sys
        a, sa = pty.openpty(); b, sb = pty.openpty()
        tty.setraw(sa); tty.setraw(sb)
        os.symlink(os.ttyname(sa), sys.argv[1]); os.symlink(os.ttyname(sb), sys.argv[2])
        while True:
          for source in select.select([a,b], [], [])[0]:
            data=os.read(source,65536)
            os.write(b if source==a else a,data)
        """,
        bridge.toString(),
        peer.toString()
      )
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertTrue(process.isAlive(), Files.readString(log));
          assertTrue(Files.exists(bridge) && Files.exists(peer));
        });
    }

    public void close() throws Exception {
      if (process != null) {
        process.destroy();
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(3, TimeUnit.SECONDS);
        }
      }
      Files.deleteIfExists(bridge);
      Files.deleteIfExists(peer);
    }
  }
}
