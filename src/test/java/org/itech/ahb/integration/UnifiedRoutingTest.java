package org.itech.ahb.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.controller.AnalyzerInputController;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
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
 * the normalized body contract for each listener type.
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
            boolean hasSourceHeaders = exchange.getRequestHeaders().keySet().stream()
                .map(String::toLowerCase)
                .anyMatch(name -> name.startsWith("x-source-") || name.equals("x-analyzer-id"));
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            lastRequest.set(new CapturedRequest(path, body, hasSourceHeaders));
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

        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        HttpForwardingRouter forwardingRouter = new HttpForwardingRouter(httpConfig, null, registry);
        registry.register("/dev/ttyUSB0", analyzer("SERIAL-001", "ASTM"));
        registry.register("/dev/ttyUSB1", analyzer("SERIAL-HL7-001", "HL7"));
        registry.register("/dev/ttyUSB2", analyzer("SERIAL-CSV-001", "CSV"));
        registry.register("192.168.1.10", analyzer("HTTP-001", "ASTM"));
        registry.register("192.168.1.20", analyzer("HTTP-002", "HL7"));
        registry.register("192.168.1.30", analyzer("HTTP-003", "CSV"));
        registry.register("192.168.1.40", analyzer("MINDRAY", "ASTM"));
        registry.register("192.168.1.50", analyzer("ANALYZER-APP-LAB-FAC", "HL7"));
        registry.register("192.168.1.51", analyzer("ANALYZER-APP-LAB-FAC", "HL7"));
        registry.register("192.168.1.60", analyzer("HTTP-004", "ASTM"));
        registry.register("unknown", analyzer("TEST", "ASTM"));
        registry.register("/tmp/quantstudio", fileAnalyzer("QUANTSTUDIO-001"));

        AnalyzerIdentifier identifier = new AnalyzerIdentifier(registry);
        normalizer = new MessageNormalizer(forwardingRouter, identifier, null);

        serialHandler = new SerialMessageHandler(normalizer);

        fileHandler = new FileMessageHandler(registry, httpConfig);

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

    private void assertNormalizedRequest(
            CapturedRequest request, String connectionId, String rawAnalyzerCode) {
        assertTrue(request.path().endsWith("/analyzer/fhir"),
                "Path should end with /analyzer/fhir, got: " + request.path());
        assertTrue(request.body().contains("\"resourceType\":\"Bundle\""));
        assertTrue(request.body().contains(connectionId),
                "Normalized bundle must carry the exact Bridge connection identity");
        assertTrue(request.body().contains(rawAnalyzerCode),
                "Normalized bundle must preserve the raw analyzer code");
        assertFalse(request.hasSourceHeaders(),
                "Normalized delivery must carry source context in the closed FHIR body, not routing headers");
    }

    private String astmResult(String accession, String code, String value) {
        return "H|\\^&|||TEST|||||||P|1|20260205120000\r"
                + "P|1||PATIENT-1\r"
                + "O|1|" + accession + "\r"
                + "R|1|^^^" + code + "|" + value + "|unit\r"
                + "L|1|N";
    }

    @Nested
    @DisplayName("Serial Handler Routing")
    class SerialHandlerTests {

        @Test
        @DisplayName("Serial ASTM message routes as the normalized result contract")
        void serialAstmRoutesCorrectly() throws Exception {
            resetLatch();
            String astmMessage = astmResult("12345", "WBC", "7.5");

            serialHandler.handleMessage(astmMessage, "/dev/ttyUSB0", "SERIAL-001", Protocol.ASTM);

            CapturedRequest req = awaitRequest();
            assertNormalizedRequest(req, "bridge-serial-001", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"ASTM\""));
            assertTrue(req.body().contains("\"valueCode\":\"SERIAL\""));
        }

        @Test
        @DisplayName("Serial HL7 message routes as the normalized result contract")
        void serialHl7RoutesCorrectly() throws Exception {
            resetLatch();
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                    "PID|1||12345\rOBX|1|NM|WBC||7.5|10^3/uL";

            serialHandler.handleMessage(hl7Message, "/dev/ttyUSB1", null, Protocol.HL7);

            CapturedRequest req = awaitRequest();
            assertNormalizedRequest(req, "bridge-serial-hl7-001", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"HL7\""));
            assertTrue(req.body().contains("\"valueCode\":\"SERIAL\""));
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
            assertTrue(req.body().contains("\"resourceType\":\"Bundle\""), "Expected FHIR bundle body");
            assertTrue(req.body().contains("bridge-quantstudio-001"));
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

            String astmMessage = astmResult("12345", "WBC", "7.5");

            var response = httpController.receiveAnalyzerMessage(astmMessage, null, null, null, mockHttpRequest);

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest req = awaitRequest();
            assertNormalizedRequest(req, "bridge-http-001", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"ASTM\""));
            assertTrue(req.body().contains("\"valueCode\":\"HTTP\""));
        }

        @Test
        @DisplayName("HTTP HL7 routes as the normalized result contract")
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
            assertNormalizedRequest(req, "bridge-http-002", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"HL7\""));
            assertTrue(req.body().contains("\"valueCode\":\"HTTP\""));
        }

    }

    @Nested
    @DisplayName("ASTM TCP Adapter Routing")
    class ASTMAdapterTests {

        @Test
        @DisplayName("ASTM TCP message routes as the normalized result contract")
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
            assertNormalizedRequest(req, "bridge-mindray", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"ASTM\""));
            assertTrue(req.body().contains("\"valueCode\":\"TCP\""));
        }

        @Test
        @DisplayName("ASTM with null sourceIp uses unknown")
        void astmNullSourceIpUsesUnknown() throws Exception {
            resetLatch();
            DefaultASTMMessage message = new DefaultASTMMessage(astmResult("12345", "WBC", "7.5"));

            astmAdapter.handle(message, null);

            CapturedRequest req = awaitRequest();
            assertNormalizedRequest(req, "bridge-test", "WBC");
        }
    }

    @Nested
    @DisplayName("MLLP Handler Routing")
    class MLLPHandlerTests {

        @Test
        @DisplayName("MLLP HL7 routes by the source-bound Bridge connection")
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
            assertNormalizedRequest(req, "bridge-analyzer-app-lab-fac", "WBC");
            assertTrue(req.body().contains("\"valueCode\":\"HL7\""));
            assertTrue(req.body().contains("\"valueCode\":\"MLLP\""));
        }
    }

    private record CapturedRequest(
            String path,
            String body,
            boolean hasSourceHeaders
    ) {}

    private AnalyzerRuntimeRegistry.AnalyzerEntry analyzer(String id, String expectedProtocol) {
        AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
        entry.setId(id);
        entry.setBridgeConnectionId("bridge-" + id.toLowerCase());
        entry.setProfileId("site." + id.toLowerCase());
        entry.setProfileRevision(1);
        entry.setExpectedProtocol(expectedProtocol);
        entry.setControlResultRecognition(ControlResultRecognition.none());
        entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
        if (!"HL7".equals(expectedProtocol)) {
            entry.setAstmResultRecordSelection(org.itech.ahb.profile.AstmResultRecordSelection.all());
        }
        entry.setCodeToLoinc(Map.of("WBC", "6690-2", "RBC", "789-8"));
        return entry;
    }

    private AnalyzerRuntimeRegistry.AnalyzerEntry fileAnalyzer(String id) {
        AnalyzerRuntimeRegistry.AnalyzerEntry entry = analyzer(id, "FILE");
        entry.setBridgeConnectionId("bridge-" + id.toLowerCase());
        entry.setProfileId("site." + id.toLowerCase());
        entry.setProfileRevision(1);
        entry.setControlResultRecognition(ControlResultRecognition.none());
        entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
        entry.setTabularResultValueSelection(TabularResultValueSelection.resultOnly());
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
