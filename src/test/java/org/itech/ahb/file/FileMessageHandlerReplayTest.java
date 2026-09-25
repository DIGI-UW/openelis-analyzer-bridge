package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMessageHandlerReplayTest {

  @TempDir
  Path directory;

  private final ObjectMapper json = new ObjectMapper();
  private final List<JsonNode> deliveries = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger rejectRequest = new AtomicInteger(-1);
  private final AtomicBoolean loseAcknowledgment = new AtomicBoolean();
  private HttpServer server;
  private AnalyzerRuntimeRegistry registry;
  private HTTPForwardServerConfigurationProperties config;
  private AnalyzerEntry entry;
  private org.itech.ahb.outbox.OutboxTestSupport outbox;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      deliveries.add(json.readTree(exchange.getRequestBody().readAllBytes()));
      if (!loseAcknowledgment.get()) {
        exchange.sendResponseHeaders(deliveries.size() == rejectRequest.get() ? 503 : 200, -1);
      }
      exchange.close();
    });
    server.start();
    config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/analyzer"));
    config.setReadTimeoutSeconds(2);
    registry = new AnalyzerRuntimeRegistry();
    entry = new AnalyzerEntry();
    entry.setId("oe-1");
    entry.setName("Test connection");
    entry.setBridgeConnectionId("connection-1");
    entry.setProfileId("site.replay-test");
    entry.setProfileRevision(1);
    entry.setProfileFingerprint("sha256:" + "2".repeat(64));
    entry.setExpectedProtocol("FILE");
    entry.setColumnMappings(Map.of("Sample", "sampleId", "Test", "testCode", "Result", "result"));
    entry.setDelimiter(",");
    entry.setTabularResultValueSelection(TabularResultValueSelection.resultOnly());
    entry.setControlResultRecognition(
      ControlResultRecognition.rules(
        List.of(new ControlRecognitionRule("control-prefix", "SPECIMEN_ID_PREFIX", null, "QC-", null, null))
      )
    );
    entry.setRecognitionFingerprint("sha256:" + "1".repeat(64));
    registry.register(directory + "#connection-1", entry);
  }

  @AfterEach
  void stopServer() {
    if (outbox != null) outbox.close();
    if (server != null) server.stop(0);
  }

  @Test
  void partialDeliverySurvivesSourceDeletionAndOnlyUndeliveredAccessionRetries() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\nQC-1,T1,3\n");
    byte[] original = Files.readAllBytes(file);
    rejectRequest.set(2);
    var receipt = handler().processFile(file, "oe-1");
    assertTrue(deliveries.isEmpty(), "receipt must not depend on an HTTP request");
    assertArrayEquals(original, outbox.store.rawBytes(receipt.getOutboxReceiptId()).orElseThrow());
    outbox.dispatcher.dispatchDue();
    assertEquals(2, deliveries.size());
    Files.delete(file);
    reopen();
    outbox.dispatcher.dispatchDue();
    assertEquals(3, deliveries.size(), "already accepted accession must not be sent again");
    assertEquals(id(1), id(2));
    assertEquals(deliveries.get(1), deliveries.get(2), "retry must retain exact normalized payload");
    assertNotEquals(id(0), id(1));
    assertEquals(2, outbox.store.countsByState().get(org.itech.ahb.outbox.OutboxState.DELIVERED));
    assertEquals(
      java.util.Set.of("PATIENT", "CONTROL"),
      deliveries.subList(0, 2).stream().map(this::classification).collect(java.util.stream.Collectors.toSet())
    );
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = { "xlsx", "xls" })
  void binaryReceiptRecoversWithItsExplicitAssayAndPinAfterSourceAndLiveConfigurationAreGone(String extension)
    throws Exception {
    Path file = directory.resolve("results." + extension);
    try (
      org.apache.poi.ss.usermodel.Workbook workbook = extension.equals("xlsx")
        ? new org.apache.poi.xssf.usermodel.XSSFWorkbook()
        : new org.apache.poi.hssf.usermodel.HSSFWorkbook()
    ) {
      var sheet = workbook.createSheet("Results");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("Sample");
      header.createCell(1).setCellValue("Test");
      header.createCell(2).setCellValue("Result");
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("BINARY-1");
      row.createCell(1).setCellValue("");
      row.createCell(2).setCellValue(12.5);
      try (var output = Files.newOutputStream(file)) {
        workbook.write(output);
      }
    }
    byte[] bytes = Files.readAllBytes(file);
    entry.setFileTestCode("DEFAULT-A");
    var receipt = handler().processFile(file, "oe-1", "SELECTED-B");
    assertArrayEquals(bytes, outbox.store.rawBytes(receipt.getOutboxReceiptId()).orElseThrow());
    assertEquals(0, deliveries.size());
    Files.delete(file);
    registry = new AnalyzerRuntimeRegistry();
    reopen();
    outbox.dispatcher.dispatchDue();
    assertEquals(1, deliveries.size());
    assertTrue(deliveries.get(0).toString().contains("SELECTED-B"));
    assertFalse(deliveries.get(0).toString().contains("DEFAULT-A"));
    var delivered = outbox.store
      .list(org.itech.ahb.outbox.OutboxQuery.inState(org.itech.ahb.outbox.OutboxState.DELIVERED, 10))
      .get(0);
    assertEquals("connection-1", delivered.connectionId());
    assertEquals("site.replay-test", delivered.profileId());
    assertEquals(1, delivered.profileRevision());
    assertArrayEquals(bytes, outbox.store.rawBytes(delivered.id()).orElseThrow());
    var context = json.readTree(outbox.store.fileContext(delivered.id()).orElseThrow());
    assertEquals("sha256:" + "2".repeat(64), context.path("profileFingerprint").asText());
    assertEquals("SELECTED-B", context.path("selectedTestCode").asText());
    var observation = java.util.stream.StreamSupport.stream(deliveries.get(0).path("entry").spliterator(), false)
      .map(item -> item.path("resource"))
      .filter(item -> "Observation".equals(item.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
    assertEquals(12.5, observation.path("valueQuantity").path("value").asDouble());
  }

  @Test
  void malformedBinaryStaysInCommonDeadQueueAndCanRetryWithoutOriginalFile() throws Exception {
    byte[] bytes = { 0, (byte) 255, 1, 2 };
    Path file = Files.write(directory.resolve("broken.xlsx"), bytes);
    var receipt = handler().processFile(file, "oe-1");
    Files.delete(file);
    reopen();
    outbox.dispatcher.dispatchDue();
    var entry = outbox.store.get(receipt.getOutboxReceiptId()).orElseThrow();
    assertEquals(org.itech.ahb.outbox.OutboxState.DMQ, entry.state());
    assertArrayEquals(bytes, outbox.store.rawBytes(entry.id()).orElseThrow());
    outbox.store.requestRetry(entry.id(), "operator", java.time.Instant.now());
    outbox.dispatcher.dispatchDue();
    assertEquals(org.itech.ahb.outbox.OutboxState.DMQ, outbox.store.get(entry.id()).orElseThrow().state());
    assertArrayEquals(bytes, outbox.store.rawBytes(entry.id()).orElseThrow());
    assertTrue(deliveries.isEmpty());
  }

  @Test
  void lostAcknowledgmentRetriesTheSameAcceptedDelivery() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\n");
    loseAcknowledgment.set(true);
    handler().processFile(file, "oe-1");
    outbox.dispatcher.dispatchDue();
    assertFalse(deliveries.isEmpty());
    int beforeRetry = deliveries.size();
    Files.delete(file);
    loseAcknowledgment.set(false);
    reopen();
    outbox.dispatcher.dispatchDue();
    assertTrue(deliveries.size() > beforeRetry);
    assertEquals(
      1,
      deliveries.stream().map(bundle -> bundle.path("identifier").path("value").asText()).distinct().count()
    );
    assertTrue(deliveries.stream().allMatch(bundle -> bundle.equals(deliveries.get(0))));
  }

  @Test
  void renameIsADuplicateAndAChangedAssayIsAnExplicitConflict() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,,2\n");
    handler().processFile(file, "oe-1", "ASSAY-A");
    outbox.dispatcher.dispatchDue();
    Path renamed = Files.move(file, directory.resolve("renamed.csv"));
    handler().processFile(renamed, "oe-1", "ASSAY-A");
    outbox.dispatcher.dispatchDue();
    assertEquals(1, deliveries.size());
    assertThrows(
      FileMessageHandler.FileProcessingException.class,
      () -> handler().processFile(renamed, "oe-1", "ASSAY-B")
    );
    assertEquals(1, outbox.store.countsByState().get(org.itech.ahb.outbox.OutboxState.DELIVERED));
  }

  @Test
  void exhaustedDeliveryCanBeRetriedByAnOperatorAfterSourceDeletionAndRestart() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\n");
    handler().processFile(file, "oe-1");
    outbox.properties.getRetry().setMaxAttempts(1);
    rejectRequest.set(1);
    outbox.dispatcher.dispatchDue();
    var held = outbox.store
      .list(org.itech.ahb.outbox.OutboxQuery.inState(org.itech.ahb.outbox.OutboxState.DMQ, 10))
      .get(0);
    assertEquals(org.itech.ahb.outbox.FailureReason.RETRY_EXHAUSTED, held.failureReason());
    Files.delete(file);
    reopen();
    outbox.store.requestRetry(held.id(), "operator", java.time.Instant.now());
    outbox.dispatcher.dispatchDue();
    assertEquals(org.itech.ahb.outbox.OutboxState.DELIVERED, outbox.store.get(held.id()).orElseThrow().state());
    assertEquals("operator", outbox.store.get(held.id()).orElseThrow().retryRequestedBy());
    assertEquals(2, deliveries.size());
    assertEquals(deliveries.get(0), deliveries.get(1));
  }

  @Test
  void uploadPreservesUncapturedSameNameSourceAndQueuesItsOwnExactBytes() throws Exception {
    Path source = csv("results.csv", "ORIGINAL,T1,7\n");
    byte[] original = Files.readAllBytes(source);
    byte[] uploaded = "Sample,Test,Result\nUPLOADED,T1,9\n".getBytes(StandardCharsets.UTF_8);
    entry.setFileDirectory(directory.toString());
    entry.setMappedTestCodes(java.util.Set.of("T1"));
    var handler = handler();
    var state = new SqliteFileStateStore(directory.resolve("state.db"));
    var watcher = new FileWatcher(new FileConfig(), handler, state);
    try {
      watcher.addWatchDirectory(directory, "*.csv", "oe-1");
      var controller = new org.itech.ahb.controller.FileUploadController(
        registry,
        handler,
        new org.itech.ahb.fhir.FileNameSelfDeclarationScanner(),
        watcher
      );
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      controller.uploadFile(
        "oe-1",
        "T1",
        new org.springframework.mock.web.MockMultipartFile("file", "results.csv", "text/csv", uploaded),
        response
      );
      assertEquals(200, response.getStatus(), response.getContentAsString());
      assertArrayEquals(original, Files.readAllBytes(source), "an undiscovered source must never be overwritten");
      var queued = outbox.store
        .list(org.itech.ahb.outbox.OutboxQuery.inState(org.itech.ahb.outbox.OutboxState.RECEIVED, 10))
        .get(0);
      assertArrayEquals(uploaded, outbox.store.rawBytes(queued.id()).orElseThrow());
      handler.processFile(source, "oe-1");
      outbox.dispatcher.dispatchDue();
      assertEquals(2, deliveries.size(), "both distinct inputs must remain deliverable");
      assertTrue(deliveries.stream().anyMatch(bundle -> bundle.toString().contains("UPLOADED")));
      assertTrue(deliveries.stream().anyMatch(bundle -> bundle.toString().contains("ORIGINAL")));
    } finally {
      watcher.stop();
      state.close();
    }
  }

  @Test
  void unavailableDurableStoreNeverAcknowledgesTheFile() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\n");
    var handler = handler();
    outbox.store.close();
    assertThrows(FileMessageHandler.FileProcessingException.class, () -> handler.processFile(file, "oe-1"));
    assertTrue(deliveries.isEmpty());
    assertTrue(Files.exists(file));
  }

  private void reopen() {
    if (outbox != null) outbox.close();
    outbox = org.itech.ahb.outbox.OutboxTestSupport.create(directory.resolve("outbox"), config, registry);
    outbox.store.recoverInterrupted(java.time.Instant.now());
  }

  private Path csv(String name, String rows) throws IOException {
    return Files.writeString(directory.resolve(name), "Sample,Test,Result\n" + rows, StandardCharsets.UTF_8);
  }

  private FileMessageHandler handler() {
    if (outbox == null) outbox = org.itech.ahb.outbox.OutboxTestSupport.create(
      directory.resolve("outbox"),
      config,
      registry
    );
    return outbox.fileHandler(registry);
  }

  private String id(int index) {
    return deliveries.get(index).path("identifier").path("value").asText();
  }

  private String classification(JsonNode bundle) {
    for (JsonNode entry : bundle.path("entry")) {
      JsonNode resource = entry.path("resource");
      if (!"Observation".equals(resource.path("resourceType").asText())) continue;
      for (JsonNode extension : resource.path("extension")) {
        if (
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-classification".equals(
              extension.path("url").asText()
            )
        ) return extension.path("valueCode").asText();
      }
    }
    throw new AssertionError("Delivery has no normalized result classification");
  }
}
