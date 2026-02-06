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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
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

/**
 * Integration tests verifying all 5 transport listeners route through MessageNormalizer
 * to HttpForwardingRouter (M7: Message Normalizer milestone).
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

        httpServer.createContext("/api/OpenELIS-Global/analyzer/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String sourceProtocol = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_PROTOCOL);
            String sourceTransport = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_TRANSPORT);
            String sourceId = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_ID);
            String analyzerId = exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_ANALYZER_ID);
            String sourceAnalyzerIp = exchange.getRequestHeaders().getFirst(
                HttpForwardingRouter.HEADER_SOURCE_ANALYZER_IP);

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            lastRequest.set(new CapturedRequest(
                path, body, sourceProtocol, sourceTransport, sourceId, analyzerId, sourceAnalyzerIp));
            requestLatch.countDown();

            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("OK".getBytes());
            } finally {
                exchange.close();
            }
        });
        httpServer.start();

        // Wire full M7 pipeline
        HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
        httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort + "/api/OpenELIS-Global/analyzer"));

        HttpForwardingRouter forwardingRouter = new HttpForwardingRouter(httpConfig, null);
        AnalyzerIdentifier identifier = new AnalyzerIdentifier(null);
        normalizer = new MessageNormalizer(forwardingRouter, identifier);

        serialHandler = new SerialMessageHandler(normalizer);

        FileConfig fileConfig = new FileConfig();
        fileConfig.setEnabled(true);
        CSVParser csvParser = new CSVParser(fileConfig);
        fileHandler = new FileMessageHandler(csvParser, fileConfig, normalizer);

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
        @DisplayName("File CSV drop routes to /analyzer/csv with correct headers")
        void fileCsvRoutesCorrectly() throws Exception {
            resetLatch();
            Path csvFile = tempDir.resolve("results.csv");
            String csvContent = "SampleID,TestCode,Result,Units\n12345,WBC,7.5,10^3/uL\n12346,RBC,4.8,10^6/uL";
            Files.writeString(csvFile, csvContent);

            fileHandler.processFile(csvFile, "QUANTSTUDIO-001");

            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/csv"), "Path should end with /csv, got: " + req.path());
            assertEquals("CSV", req.sourceProtocol());
            assertEquals("FILE", req.sourceTransport());
            assertTrue(req.sourceId().contains("results.csv"));
            assertEquals("QUANTSTUDIO-001", req.analyzerId());
        }
    }

    @Nested
    @DisplayName("HTTP Input Controller Routing")
    class HttpInputControllerTests {

        @Test
        @DisplayName("HTTP POST ASTM routes to /analyzer/astm")
        void httpAstmRoutesCorrectly() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.10");
            when(mockHttpRequest.getHeader("X-Real-IP")).thenReturn(null);

            String astmMessage = "H|\\^&|||TEST|||||||P|1|20260205120000\rP|1||12345\rL|1|N";

            var response = httpController.receiveAnalyzerMessage(astmMessage, null, null, mockHttpRequest);

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest req = awaitRequest();
            assertTrue(req.path().endsWith("/astm"), "Path should end with /astm, got: " + req.path());
            assertEquals("ASTM", req.sourceProtocol());
            assertEquals("HTTP", req.sourceTransport());
            assertEquals("192.168.1.10", req.sourceId());
            assertEquals("192.168.1.10", req.sourceAnalyzerIp());
        }

        @Test
        @DisplayName("HTTP POST HL7 routes to /analyzer/hl7")
        void httpHl7RoutesCorrectly() throws Exception {
            resetLatch();
            when(mockHttpRequest.getRemoteAddr()).thenReturn("192.168.1.20");
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                    "PID|1||12345\rOBX|1|NM|WBC||7.5";

            var response = httpController.receiveAnalyzerMessage(hl7Message, "application/hl7-v2", null, mockHttpRequest);

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
            when(mockHttpRequest.getHeader(anyString())).thenReturn(null);

            String csvMessage = "SampleID,TestCode,Result\n12345,WBC,7.5\n12346,RBC,4.8";

            var response = httpController.receiveAnalyzerMessage(csvMessage, "text/csv", null, mockHttpRequest);

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
    }

    private record CapturedRequest(
            String path,
            String body,
            String sourceProtocol,
            String sourceTransport,
            String sourceId,
            String analyzerId,
            String sourceAnalyzerIp
    ) {}
}
