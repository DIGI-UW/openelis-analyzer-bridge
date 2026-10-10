package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerConnectionCatalog;
import org.itech.ahb.connection.AnalyzerConnectionCatalog.FileDirectoryClaim;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.file.SqliteFileStateStore;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeFileResetTransportTest {

  @TempDir
  Path directory;

  @Test
  void resetKeepsRetainedBytesWhileDeliveryContinuesAndTheConnectionRemainsUsable() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path file = watched.resolve("result.csv");
    byte[] csv = "Sample,Test,Result\nPATIENT-1,T1,2\n".getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
    CountDownLatch received = new CountDownLatch(1);
    CountDownLatch acknowledge = new CountDownLatch(1);
    CountDownLatch resetting = new CountDownLatch(1);
    AtomicInteger deliveries = new AtomicInteger();
    AtomicReference<Throwable> serverError = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      try {
        var bundle = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
        assertEquals("Bundle", bundle.path("resourceType").asText());
        deliveries.incrementAndGet();
        received.countDown();
        assertTrue(acknowledge.await(10, TimeUnit.SECONDS), "receiver acknowledgment was not released");
        exchange.sendResponseHeaders(200, -1);
      } catch (Throwable failure) {
        serverError.compareAndSet(null, failure);
      } finally {
        exchange.close();
      }
    });
    server.start();
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("owner");
    entry.setBridgeConnectionId("connection-1");
    entry.setProfileId("site.reset-test");
    entry.setProfileRevision(1);
    entry.setProfileFingerprint("sha256:" + "1".repeat(64));
    entry.setExpectedProtocol("FILE");
    entry.setFileDirectory(watched.toString());
    entry.setFilePattern("*.csv");
    entry.setFileTestCode("T1");
    entry.setColumnMappings(Map.of("Sample", "sampleId", "Test", "testCode", "Result", "result"));
    entry.setDelimiter(",");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "1".repeat(64));
    entry.setTabularResultValueSelection(TabularResultValueSelection.resultOnly());
    registry.register(watched + "#connection-1", entry);
    HTTPForwardServerConfigurationProperties http = new HTTPForwardServerConfigurationProperties();
    http.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    http.setReadTimeoutSeconds(10);
    var outbox = org.itech.ahb.outbox.OutboxTestSupport.create(
      directory.resolve("outbox"),
      http,
      registry
    ).startDispatcher();
    FileMessageHandler handler = outbox.fileHandler(registry);
    FileConfig config = new FileConfig();
    config.setImportRoots(List.of(directory.toString()));
    config.setEnabled(true);
    config.setPollIntervalMs(50);
    config.setFileStabilityTimeoutMs(50);
    FileWatcher watcher = new FileWatcher(config, handler, store);
    AnalyzerConnectionCatalog catalog = mock(AnalyzerConnectionCatalog.class);
    when(catalog.fileDirectoryClaims()).thenReturn(List.of(new FileDirectoryClaim("owner", watched, "*.csv")));
    BridgeAdminController admin = new BridgeAdminController(registry, store, catalog, watcher);
    var executor = Executors.newFixedThreadPool(3);
    try {
      watcher.start();
      watcher.addWatchDirectory(watched, "*.csv", "owner");
      var initial = executor.submit(() -> {
        Files.write(file, csv);
        return null;
      });
      assertTrue(received.await(5, TimeUnit.SECONDS), "no real HTTP delivery reached the receiver");
      initial.get(5, TimeUnit.SECONDS);
      var reset = executor.submit(() -> admin.reset("owner"));
      var resetBeforeReceiverAck = reset.get(5, TimeUnit.SECONDS);
      assertEquals(200, resetBeforeReceiverAck.getStatusCode().value());
      assertFalse(Files.exists(file));
      var retained = outbox.store.list(org.itech.ahb.outbox.OutboxQuery.all(10));
      assertEquals(1, retained.size());
      assertArrayEquals(
        csv,
        outbox.store.rawBytes(retained.get(0).id()).orElseThrow(),
        "reset must not remove bytes still awaiting receiver acknowledgment"
      );
      acknowledge.countDown();
      initial.get(5, TimeUnit.SECONDS);
      var result = reset.get(5, TimeUnit.SECONDS);
      assertEquals(200, result.getStatusCode().value());
      assertEquals(1, result.getBody().get("filesRemoved"));
      assertEquals(1, result.getBody().get("stateRowsRemoved"));
      assertFalse(Files.exists(file));
      assertTrue(store.get("owner", hash).isEmpty());

      Files.write(watched.resolve("after-reset.csv"), csv);
      org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
          () -> assertEquals(1, outbox.store.countsByState().get(org.itech.ahb.outbox.OutboxState.DELIVERED))
        );
      assertEquals(1, deliveries.get(), "the same file after reset must reuse the retained delivery identity");
      assertNull(serverError.get());
    } finally {
      acknowledge.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
      outbox.close();
      server.stop(0);
    }
  }

}
