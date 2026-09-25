package org.itech.ahb.connection;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.controller.FileUploadController;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.file.SqliteFileStateStore;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/** Real FILE/upload/HTTP transport with disk-backed connection and processing-state recovery. */
class FileConnectionShutdownReplayTest {

  @TempDir
  Path directory;

  private final ObjectMapper json = new ObjectMapper();
  private final List<ObjectNode> deliveries = new CopyOnWriteArrayList<>();
  private final CountDownLatch receivedSecond = new CountDownLatch(1);
  private final CountDownLatch releaseSecond = new CountDownLatch(1);
  private final AtomicReference<Throwable> receiverFailure = new AtomicReference<>();
  private HttpServer receiver;
  private ObjectNode profile;
  private FileWatcher watcher;
  private SqliteFileStateStore store;
  private AnalyzerConnectionCatalog connections;
  private AnalyzerRuntimeRegistry registry;
  private FileUploadController uploads;
  private org.itech.ahb.outbox.OutboxTestSupport outbox;

  @Test
  void acceptedUploadSurvivesSourceDeletionAndRestartWithoutRepeatingDeliveredAccessions() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    byte[] csv =
      ("Sample ID;TargetName;Calc. Conc.;Type\n" +
        "SAMPLE-1;VIH-1;17.5;Patient\nC+CONTROL;VIH-1;2;Positive\n").getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/analyzer/fhir", exchange -> {
      try {
        deliveries.add((ObjectNode) json.readTree(exchange.getRequestBody()));
        int status = 200;
        if (deliveries.size() == 2) {
          receivedSecond.countDown();
          assertTrue(releaseSecond.await(10, TimeUnit.SECONDS));
          status = 503;
        }
        exchange.sendResponseHeaders(status, -1);
      } catch (Throwable failure) {
        receiverFailure.compareAndSet(null, failure);
      } finally {
        exchange.close();
      }
    });
    receiver.start();
    var executor = Executors.newFixedThreadPool(2);
    try {
      profile = (ObjectNode) json.readTree(getClass().getResourceAsStream("/analyzer-profiles/fluorocycler-xt.json"));
      profile.withObject("profileMeta").put("id", "test.file-shutdown");
      profile.withObject("protocol").put("format", "CSV");
      profile.putArray("supported_extensions").add(".csv");
      profile.putArray("result_value_order").add("result").add("interpretation");
      profile.withObject("configDefaults").put("fileFormat", "CSV").put("filePattern", "*.csv").put("delimiter", ";");
      profile
        .withObject("catalog")
        .put("revisionFingerprint", new ProfileFingerprintService().revisionFingerprint(profile));
      var schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      assertTrue(
        schemas
          .getSchema(json.readTree(Path.of("contracts/analyzer/v1/analyzer-profile.schema.json").toFile()))
          .validate(profile)
          .isEmpty()
      );
      reopen();
      ObjectNode request = json
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("requestId", "create-file")
        .put("clientAnalyzerId", "oe-file")
        .put("displayName", "FILE shutdown test");
      request
        .putObject("profileRef")
        .put("profileId", "test.file-shutdown")
        .put("revision", 1)
        .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
      request.putObject("values").put("directory", watched.toString());
      ObjectNode connection = connections.create(request);
      assertTrue(connection.path("readiness").path("ready").asBoolean());
      ObjectNode ack = connections.applyRuntimeCommand(
        json
          .createObjectNode()
          .put("schemaVersion", "1.0")
          .put("commandId", UUID.randomUUID().toString())
          .put("connectionId", connection.path("connectionId").asText())
          .put("action", "ACTIVATE")
          .put("expectedConfigRevision", 1)
      );
      assertEquals("ACTIVE", ack.path("actualRuntimeState").asText());
      var response = new MockHttpServletResponse();
      uploads.uploadFile("oe-file", "VIH-1", multipart("result.csv", csv), response);
      assertEquals(200, response.getStatus());
      assertTrue(response.getContentAsString().contains("received and queued"));
      assertTrue(receivedSecond.await(5, TimeUnit.SECONDS), "partial delivery never reached the HTTP receiver");
      watcher.stop();
      assertEquals("PROCESSED", store.get("oe-file", hash).orElseThrow().status().name());
      Files.delete(watched.resolve("result.csv"));
      var shutdown = executor.submit(outbox::close);
      assertThrows(
        TimeoutException.class,
        () -> shutdown.get(200, TimeUnit.MILLISECONDS),
        "dispatcher drains its in-flight delivery before closing durable state"
      );
      releaseSecond.countDown();
      shutdown.get(5, TimeUnit.SECONDS);
      assertEquals(2, deliveries.size());
      store.close();

      reopen();
      await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
          () -> assertEquals(2, outbox.store.countsByState().get(org.itech.ahb.outbox.OutboxState.DELIVERED))
        );
      watcher.stop();
      assertEquals(3, deliveries.size(), "only the undelivered accession repeats");
      assertEquals(deliveries.get(1), deliveries.get(2), "exact delivery payload and identity survive restart");
      assertNotEquals(deliveries.get(0).path("identifier"), deliveries.get(1).path("identifier"));
      for (var delivered : outbox.store.list(
        org.itech.ahb.outbox.OutboxQuery.inState(org.itech.ahb.outbox.OutboxState.DELIVERED, 10)
      )) {
        assertArrayEquals(csv, outbox.store.rawBytes(delivered.id()).orElseThrow());
      }
      var restored = registry.getRegisteredAnalyzers().values().stream().findFirst().orElseThrow();
      assertEquals(connection.path("connectionId").asText(), restored.getBridgeConnectionId());
      assertEquals("test.file-shutdown", restored.getProfileId());
      assertEquals(1, restored.getProfileRevision());
      var schema = schemas.getSchema(
        json.readTree(Path.of("contracts/analyzer/v1/normalized-fhir-bundle.schema.json").toFile())
      );
      deliveries.forEach(bundle -> assertTrue(schema.validate(bundle).isEmpty()));
      assertFalse(Files.exists(watched.resolve("result.csv")));
      assertNull(receiverFailure.get());
    } finally {
      releaseSecond.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      if (watcher != null) watcher.stop();
      if (store != null) store.close();
      if (outbox != null) outbox.close();
      receiver.stop(0);
    }
  }

  private void reopen() throws Exception {
    var profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(new ByteArrayResource(json.writeValueAsBytes(profile))),
      json,
      Clock.systemUTC()
    );
    registry = new AnalyzerRuntimeRegistry();
    store = new SqliteFileStateStore(directory.resolve("state.db"));
    var http = new HTTPForwardServerConfigurationProperties();
    http.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    http.setReadTimeoutSeconds(10);
    if (outbox != null) outbox.close();
    outbox = org.itech.ahb.outbox.OutboxTestSupport.create(
      directory.resolve("outbox"),
      http,
      registry
    ).startDispatcher();
    var handler = outbox.fileHandler(registry);
    var config = new FileConfig();
    config.setPollIntervalMs(50);
    config.setFileStabilityTimeoutMs(50);
    watcher = new FileWatcher(config, handler, store);
    watcher.start();
    var runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      watcher,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    connections = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      json,
      Clock.systemUTC(),
      UUID::randomUUID,
      runtime
    );
    uploads = new FileUploadController(registry, handler, mock(FileNameSelfDeclarationScanner.class), watcher);
  }

  private MockMultipartFile multipart(String filename, byte[] content) {
    return new MockMultipartFile("file", filename, "text/csv", content);
  }
}
