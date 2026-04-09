package org.itech.ahb.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.FhirRoutingConfig;
import org.itech.ahb.controller.AnalyzerInputController;
import org.itech.ahb.file.CSVParser;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.serial.SerialMessageHandler;
import org.itech.ahb.mllp.HapiReceivingApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Integration tests verifying all transport listeners route through the same
 * bridge -> OpenELIS forwarding path, with source registration as the
 * authoritative analyzer identity contract.
 * <p>
 * Uses com.sun.net.httpserver.HttpServer to capture forwarded HTTP requests and verify
 * correct path and headers for each listener type.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unified Routing Integration Tests (M7)")
class UnifiedRoutingTest {

    private HttpServer httpServer;
    private int serverPort;
    private MessageNormalizer normalizer;
    private SerialMessageHandler serialHandler;
    private FileMessageHandler fileHandler;
    private AnalyzerInputController httpController;
    private ASTMBridgeAdapter astmAdapter;
    private HapiReceivingApplication mllpApplication;

    private AtomicReference<CapturedRequest> lastRequest;
    private CountDownLatch requestLatch;

    @Mock
    private HttpServletRequest mockHttpRequest;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        lastRequest = new AtomicReference<>();
        requestLatch = new CountDownLatch(1);

        // Start embedded HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        var captureHandler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            String path = exchange.getRequestURI().getPath();
            String sourceProtocol = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_PROTOCOL);
            String sourceTransport = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_TRANSPORT);
            String sourceId = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_ID);
            String analyzerId = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_ANALYZER_ID);
            String sourceAnalyzerIp = exchange.getRequestHeaders().getFirst(
                HttpForwardingRouter.HEADER_SOURCE_ANALYZER_IP);
            String sourcePort = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_PORT);

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            lastRequest.set(new CapturedRequest(
                path, body, sourceProtocol, sourceTransport, sourceId, analyzerId, sourceAnalyzerIp, sourcePort));
            requestLatch.countDown();

            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
            } finally {
                exchange.close();
            }
        };
        httpServer.createContext("/api/OpenELIS-Global/analyzer/", captureHandler);
        httpServer.createContext("/api/OpenELIS-Global/rest/analyzers/", captureHandler);
        httpServer.start();

        // Wire the authoritative routing pipeline:
        // source registration -> AnalyzerIdentifier -> MessageNormalizer -> single forward router.
        HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
        httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort + "/api/OpenELIS-Global/analyzer"));

        AnalyzerRegistryConfig registry = new AnalyzerRegistryConfig();
        HttpForwardingRouter forwardingRouter = new HttpForwardingRouter(httpConfig, null, null, registry);
        Map<String, AnalyzerRegistryConfig.AnalyzerEntry> analyzers = new LinkedHashMap<>();
        analyzers.put("/dev/ttyUSB0", analyzer("SERIAL-001", "ASTM"));
        analyzers.put("/dev/ttyUSB1", analyzer("SERIAL-HL7-001", "HL7"));
        analyzers.put("/dev/ttyUSB2", analyzer("SERIAL-CSV-001", "CSV"));
        analyzers.put("192.168.1.10", analyzer("HTTP-001", "ASTM"));
        analyzers.put("192.168.1.20", analyzer("HTTP-002", "HL7"));
        analyzers.put("192.168.1.30", analyzer("HTTP-003", "CSV"));
        analyzers.put("192.168.1.40", analyzer("MINDRAY", "ASTM"));
        analyzers.put("192.168.1.50", analyzer("ANALYZER-APP-LAB-FAC", "HL7"));
        analyzers.put("192.168.1.51", analyzer("ANALYZER-APP-LAB-FAC", "HL7"));
        analyzers.put("192.168.1.60", analyzer("HTTP-004", "ASTM"));
        analyzers.put("unknown", analyzer("TEST", "ASTM"));
        analyzers.put("/tmp/quantstudio", fileAnalyzer("QUANTSTUDIO-001"));
        registry.setAnalyzers(analyzers);

        AnalyzerIdentifier identifier = new AnalyzerIdentifier(registry);
        normalizer = new MessageNormalizer(forwardingRouter, identifier, null);

        serialHandler = new SerialMessageHandler(normalizer);

        FileConfig fileConfig = new FileConfig();
        fileConfig.setEnabled(true);
        CSVParser csvParser = new CSVParser(fileConfig);
        FhirRoutingConfig fhirRoutingConfig = new FhirRoutingConfig();
        fhirRoutingConfig.setUseFhir(true);
        fileHandler = new FileMessageHandler(csvParser, fhirRoutingConfig, registry, httpConfig);

        httpController = new AnalyzerInputController(normalizer);

        astmAdapter = new ASTMBridgeAdapter(normalizer);

        mllpApplication = new HapiReceivingApplication(normalizer);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void resetLatch() {
        requestLatch = new CountDownLatch(1);
    }

    private CapturedRequest awaitRequest() throws InterruptedException {
        assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "Expected HTTP request within 5 seconds");
        return lastRequest.get();
    }

    @Nested
    @DisplayName("Serial Handler Routing")
    class SerialHandlerTests {

        @Test
        @DisplayName("Serial ASTM message routes to /analyzer/astm with correct headers")
        void serialAstmRoutesCorrectly() throws Exception {
            resetLatch();
            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            serialHandler.handleMessage(astmMessage, "/dev/ttyUSB0", "SERIAL-001");

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/astm"), "Path should end with /astm, got: " + req.path());
            assertEquals("ASTM", req.sourceProtocol());
            assertEquals("SERIAL", req.sourceTransport());
            assertEquals("/dev/ttyUSB0", req.sourceId());
            assertEquals("SERIAL-001", req.analyzerId());
            assertNull(req.sourceAnalyzerIp());
            assertTrue(req.body().contains("H|\\^&"));
        }

        @Test
        @DisplayName("Serial HL7 message routes to /analyzer/hl7")
        void serialHl7RoutesCorrectly() throws Exception {
            resetLatch();
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                    "PID|1||12345\rOBX|1|NM|WBC||7.5|10^3/uL";

            serialHandler.handleMessage(hl7Message, "/dev/ttyUSB1", null);

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/hl7"), "Path should end with /hl7, got: " + req.path());
            assertEquals("HL7", req.sourceProtocol());
            assertEquals("SERIAL", req.sourceTransport());
        }

        @Test
        @DisplayName("Serial CSV message routes to /analyzer/csv")
        void serialCsvRoutesCorrectly() throws Exception {
            resetLatch();
            // ProtocolDetector requires >= 4 columns for CSV detection
            String csvMessage = "SampleID,TestCode,Result,Units\n12345,WBC,7.5,10^3/uL\n12346,RBC,4.8,10^6/uL";

            serialHandler.handleMessage(csvMessage, "/dev/ttyUSB2", null);

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/csv"), "Path should end with /csv, got: " + req.path());
            assertEquals("CSV", req.sourceProtocol());
            assertEquals("SERIAL", req.sourceTransport());
        }
    }

    @Nested
    @DisplayName("File Handler Routing")
    class FileHandlerTests {

        @Test
        @DisplayName("FILE delivery uses the same unified /analyzer/fhir forwarding path")
        void fileCsvRoutesCorrectly() throws Exception {
            resetLatch();
            Path workbookFile = createFileWorkbook(
                    tempDir.resolve("results.xlsx"),
                    new String[]{"Sample Name", "Target", "CT", "Units"},
                    new String[][]{
                        {"12345", "WBC", "7.5", "10^3/uL"},
                        {"12345", "RBC", "4.8", "10^6/uL"}
                    });

            fileHandler.processFile(workbookFile, "QUANTSTUDIO-001");

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/analyzer/fhir"),
                    "Path should end with /analyzer/fhir, got: " + req.path());
            assertEquals("QUANTSTUDIO-001", req.analyzerId());
            assertTrue(req.body().contains("\"resourceType\":\"Bundle\""), "Expected FHIR bundle body");
        }
    }

    @Nested
    @DisplayName("HTTP Input Controller Routing")
    class HttpInputControllerTests {

        @Test
        @DisplayName("HTTP ASTM uses source registration as authoritative analyzer identity")
        void httpAstmRoutesCorrectly() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.10");
            when(mockHttpRequest.getRemotePort()).thenReturn(0);
            when(mockHttpRequest.getHeader("X-Real-IP")).thenReturn(null);

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            var response = httpController.receiveAnalyzerMessage(astmMessage, null, null, null, mockHttpRequest);

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/astm"), "Path should end with /astm, got: " + req.path());
            assertEquals("ASTM", req.sourceProtocol());
            assertEquals("HTTP", req.sourceTransport());
            assertEquals("192.168.1.10", req.sourceId());
            assertEquals("192.168.1.10", req.sourceAnalyzerIp());
            assertEquals("HTTP-001", req.analyzerId(), "Registered source should determine analyzer identity");
        }

        @Test
        @DisplayName("HTTP POST HL7 routes to /analyzer/hl7")
        void httpHl7RoutesCorrectly() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.20");
            when(mockHttpRequest.getRemotePort()).thenReturn(0);
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                    "PID|1||12345\rOBX|1|NM|WBC||7.5";

            var response = httpController.receiveAnalyzerMessage(hl7Message, "application/hl7-v2", null, null, mockHttpRequest);

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/hl7"), "Path should end with /hl7, got: " + req.path());
            assertEquals("HL7", req.sourceProtocol());
            assertEquals("HTTP", req.sourceTransport());
        }

        @Test
        @DisplayName("HTTP POST CSV routes to /analyzer/csv")
        void httpCsvRoutesCorrectly() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.30");
            when(mockHttpRequest.getRemotePort()).thenReturn(0);
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            String csvMessage = "SampleID,TestCode,Result\n12345,WBC,7.5\n12346,RBC,4.8";

            var response = httpController.receiveAnalyzerMessage(csvMessage, "text/csv", null, null, mockHttpRequest);

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/csv"), "Path should end with /csv, got: " + req.path());
            assertEquals("CSV", req.sourceProtocol());
            assertEquals("HTTP", req.sourceTransport());
        }
    }

    @Nested
    @DisplayName("ASTM TCP Adapter Routing")
    class ASTMAdapterTests {

        @Test
        @DisplayName("ASTM TCP message routes to /analyzer/astm via MessageNormalizer")
        void astmTcpRoutesCorrectly() throws Exception {
            resetLatch();
            String astmMessage = "H|\\^&|||MINDRAY^BC-5380|||||||P|1|20260205120000\r" +
                    "P|1||PAT001||DOE^JOHN\r" +
                    "O|1|SAM001||^^^CBC\r" +
                    "R|1|^^^WBC|7.5|10^3/uL||N||F\r" +
                    "L|1|N";

            DefaultASTMMessage message = new DefaultASTMMessage(astmMessage);
            astmAdapter.handle(message, "192.168.1.40");

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/astm"), "Path should end with /astm, got: " + req.path());
            assertEquals("ASTM", req.sourceProtocol());
            assertEquals("TCP", req.sourceTransport());
            assertEquals("192.168.1.40", req.sourceId());
            assertTrue(req.body().contains("H|\\^&"));
        }

        @Test
        @DisplayName("ASTM with null sourceIp uses unknown")
        void astmNullSourceIpUsesUnknown() throws Exception {
            resetLatch();
            DefaultASTMMessage message = new DefaultASTMMessage("H|\\^&|||TEST\rL|1|N");

            astmAdapter.handle(message, null);

            CapturedRequest req = awaitRequest();
            assertEquals("unknown", req.sourceId());
        }
    }

    @Nested
    @DisplayName("MLLP Handler Routing")
    class MLLPHandlerTests {

        @Test
        @DisplayName("MLLP HL7 message routes to /analyzer/hl7 with X-Analyzer-Id from MSH")
        void mllpHl7RoutesCorrectly() throws Exception {
            resetLatch();
            String hl7Message = "MSH|^~\\&|ANALYZER-APP|LAB-FAC|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                    "PID|1||12345||Doe^John\r" +
                    "OBR|1||ORD001|CBC\r" +
                    "OBX|1|NM|WBC||7.5|10^3/uL||||F";

            Message message = new PipeParser().parse(hl7Message);
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("SENDING_IP", "192.168.1.50");
            metadata.put("raw-message", hl7Message);

            mllpApplication.processMessage(message, metadata);

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/hl7"), "Path should end with /hl7, got: " + req.path());
            assertEquals("HL7", req.sourceProtocol());
            assertEquals("MLLP", req.sourceTransport());
            assertEquals("192.168.1.50", req.sourceId());
            // Analyzer ID from MSH-3/MSH-4: ANALYZER-APP-LAB-FAC
            assertNotNull(req.analyzerId());
            assertTrue(req.analyzerId().contains("ANALYZER-APP"), "X-Analyzer-Id should come from MSH-3");
        }

        @Test
        @DisplayName("MLLP message with SENDING_PORT metadata forwards X-Source-Port")
        void mllpForwardsSourcePort() throws Exception {
            resetLatch();
            String hl7Message = "MSH|^~\\&|ANALYZER-APP|LAB-FAC|OPENELIS|LAB|20260205120000||ORU^R01|MSG002|P|2.5.1\r" +
                    "PID|1||12345||Doe^John\r" +
                    "OBX|1|NM|WBC||7.5|10^3/uL||||F";

            Message message = new PipeParser().parse(hl7Message);
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("SENDING_IP", "192.168.1.51");
            metadata.put("SENDING_PORT", 54321);
            metadata.put("raw-message", hl7Message);

            mllpApplication.processMessage(message, metadata);

            CapturedRequest req = awaitRequest();
            assertEquals("54321", req.sourcePort(), "X-Source-Port should be forwarded from SENDING_PORT");
        }
    }

    @Nested
    @DisplayName("X-Source-Port Header Tests")
    class SourcePortHeaderTests {

        @Test
        @DisplayName("HTTP direct connection forwards X-Source-Port")
        void httpDirectConnectionForwardsSourcePort() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.10");
            when(mockHttpRequest.getRemotePort()).thenReturn(12345);
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            httpController.receiveAnalyzerMessage(astmMessage, null, null, null, mockHttpRequest);

            CapturedRequest req = awaitRequest();
            assertEquals("12345", req.sourcePort(), "X-Source-Port should be set for direct connections");
        }

        @Test
        @DisplayName("HTTP proxied connection uses X-Forwarded-Port when available")
        void httpProxiedConnectionUsesXForwardedPort() throws Exception {
            resetLatch();

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            httpController.receiveAnalyzerMessage(
                astmMessage, null, "192.168.1.10", "54321", mockHttpRequest);

            CapturedRequest req = awaitRequest();
            assertEquals("54321", req.sourcePort(), "X-Source-Port should use X-Forwarded-Port for proxied connections");
        }

        @Test
        @DisplayName("HTTP proxied connection without X-Forwarded-Port omits X-Source-Port")
        void httpProxiedConnectionWithoutForwardedPortOmitsSourcePort() throws Exception {
            resetLatch();

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            httpController.receiveAnalyzerMessage(
                astmMessage, null, "192.168.1.10", null, mockHttpRequest);

            CapturedRequest req = awaitRequest();
            assertNull(req.sourcePort(), "X-Source-Port should be absent when proxied without X-Forwarded-Port");
        }

        @Test
        @DisplayName("HTTP X-Real-IP proxy without X-Forwarded-Port omits X-Source-Port")
        void httpXRealIpProxyWithoutForwardedPortOmitsSourcePort() throws Exception {
            resetLatch();
            when(mockHttpRequest.getHeader("X-Real-IP")).thenReturn("192.168.1.10");

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            httpController.receiveAnalyzerMessage(
                astmMessage, null, null, null, mockHttpRequest);

            CapturedRequest req = awaitRequest();
            assertNull(req.sourcePort(), "X-Source-Port should be absent when X-Real-IP indicates a proxied request without X-Forwarded-Port");
        }

        @Test
        @DisplayName("HTTP proxied connection with out-of-range X-Forwarded-Port omits X-Source-Port")
        void httpProxiedConnectionWithInvalidForwardedPortOmitsSourcePort() throws Exception {
            resetLatch();

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            httpController.receiveAnalyzerMessage(
                astmMessage, null, "192.168.1.10", "65536", mockHttpRequest);

            CapturedRequest req = awaitRequest();
            assertNull(req.sourcePort(), "X-Source-Port should be absent when X-Forwarded-Port is out of range");
        }

        @Test
        @DisplayName("X-Source-Port is preserved through normalizer enrichment when analyzerId is set")
        void sourcePortPreservedThroughNormalizerEnrichment() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.60");
            when(mockHttpRequest.getRemotePort()).thenReturn(9999);
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            // Use an ASTM message that includes a recognizable sender so the normalizer
            // may attempt to enrich the envelope with an analyzerId
            String astmMessage = "H|\\^&|||MINDRAY^BC-5380|||||||P|1|20260205120000\r" +
                    "P|1||PAT001||DOE^JOHN\r" +
                    "L|1|N";

            httpController.receiveAnalyzerMessage(astmMessage, null, null, null, mockHttpRequest);

            CapturedRequest req = awaitRequest();
            // The port must survive any normalizer envelope rebuild
            assertEquals("9999", req.sourcePort(), "X-Source-Port must be preserved through normalizer enrichment");
        }
    }

    private record CapturedRequest(
            String path,
            String body,
            String sourceProtocol,
            String sourceTransport,
            String sourceId,
            String analyzerId,
            String sourceAnalyzerIp,
            String sourcePort
    ) {}

    private AnalyzerRegistryConfig.AnalyzerEntry analyzer(String id, String expectedProtocol) {
        AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
        entry.setId(id);
        entry.setExpectedProtocol(expectedProtocol);
        return entry;
    }

    private AnalyzerRegistryConfig.AnalyzerEntry fileAnalyzer(String id) {
        AnalyzerRegistryConfig.AnalyzerEntry entry = analyzer(id, "FILE");
        entry.setColumnMappings(Map.of(
                "Sample Name", "sampleId",
                "Target", "testCode",
                "CT", "result",
                "Units", "units"));
        return entry;
    }

    private Path createFileWorkbook(Path file, String[] headers, String[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Results");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int rowIdx = 0; rowIdx < rows.length; rowIdx++) {
                Row row = sheet.createRow(rowIdx + 1);
                for (int colIdx = 0; colIdx < rows[rowIdx].length; colIdx++) {
                    row.createCell(colIdx).setCellValue(rows[rowIdx][colIdx]);
                }
            }
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }
}
