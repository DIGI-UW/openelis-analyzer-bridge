package org.itech.ahb.serial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.serial.SerialMessageHandler.HandleResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SerialMessageHandler class.
 * <p>
 * Uses an embedded HTTP server to test message forwarding without external dependencies.
 * </p>
 */
class SerialMessageHandlerTest {

    private HttpServer httpServer;
    private SerialMessageHandler handler;
    private HTTPForwardServerConfigurationProperties httpConfig;
    private int serverPort;

    // Capture received requests
    private AtomicReference<String> receivedBody;
    private AtomicReference<String> receivedContentType;
    private AtomicReference<String> receivedSourceId;
    private AtomicReference<String> receivedTransport;
    private AtomicReference<String> receivedPath;
    private int responseCode = 200;
    private String responseBody = "OK";

    @BeforeEach
    void setUp() throws IOException {
        receivedBody = new AtomicReference<>();
        receivedContentType = new AtomicReference<>();
        receivedSourceId = new AtomicReference<>();
        receivedTransport = new AtomicReference<>();
        receivedPath = new AtomicReference<>();

        // Start embedded HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        // Handle all analyzer endpoints
        httpServer.createContext("/api/OpenELIS-Global/analyzer/", exchange -> {
            handleRequest(exchange);
        });

        httpServer.start();

        // Configure handler
        httpConfig = new HTTPForwardServerConfigurationProperties();
        httpConfig.setUri(URI.create("http://localhost:" + serverPort));

        handler = new SerialMessageHandler(httpConfig);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        // Capture request details
        receivedPath.set(exchange.getRequestURI().getPath());
        receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        receivedSourceId.set(exchange.getRequestHeaders().getFirst(SerialMessageHandler.SOURCE_ID_HEADER));
        receivedTransport.set(exchange.getRequestHeaders().getFirst(SerialMessageHandler.TRANSPORT_HEADER));

        // Read body
        try (InputStream is = exchange.getRequestBody()) {
            receivedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }

        // Send response
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    @Nested
    @DisplayName("Protocol Detection Tests")
    class ProtocolDetectionTests {

        @Test
        @DisplayName("Should detect and route ASTM message")
        void shouldDetectAndRouteASTMMessage() {
            String astmMessage = "H|\\^&|||ANALYZER|||||||P|1|20260205120000\r" +
                                "P|1||12345\r" +
                                "L|1|N";

            HandleResult result = handler.handleMessage(astmMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("/api/OpenELIS-Global/analyzer/astm", receivedPath.get());
            assertEquals(astmMessage, receivedBody.get());
            assertEquals("text/plain", receivedContentType.get());
        }

        @Test
        @DisplayName("Should detect and route HL7 message")
        void shouldDetectAndRouteHL7Message() {
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                               "PID|1||12345||DOE^JOHN\r" +
                               "OBX|1|NM|WBC||7.5|10^3/uL";

            HandleResult result = handler.handleMessage(hl7Message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("/api/OpenELIS-Global/analyzer/hl7", receivedPath.get());
            assertEquals(hl7Message, receivedBody.get());
            assertEquals("application/hl7-v2", receivedContentType.get());
        }

        @Test
        @DisplayName("Should detect and route CSV message")
        void shouldDetectAndRouteCSVMessage() {
            String csvMessage = "SampleID,TestCode,Result,Unit,Flag\r\n" +
                               "12345,WBC,7.5,10^3/uL,N\r\n" +
                               "12345,RBC,4.8,10^6/uL,N";

            HandleResult result = handler.handleMessage(csvMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("/api/OpenELIS-Global/analyzer/csv", receivedPath.get());
            assertEquals(csvMessage, receivedBody.get());
            assertEquals("text/csv", receivedContentType.get());
        }

        @Test
        @DisplayName("Should route unknown protocol to raw endpoint")
        void shouldRouteUnknownToRawEndpoint() {
            String unknownMessage = "This is some unknown format";

            HandleResult result = handler.handleMessage(unknownMessage, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("/api/OpenELIS-Global/analyzer/raw", receivedPath.get());
        }
    }

    @Nested
    @DisplayName("Header Tests")
    class HeaderTests {

        @Test
        @DisplayName("Should include source ID header")
        void shouldIncludeSourceIdHeader() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertEquals("/dev/ttyUSB0", receivedSourceId.get());
        }

        @Test
        @DisplayName("Should include transport header")
        void shouldIncludeTransportHeader() {
            String message = "H|\\^&|||TEST";

            handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertEquals("SERIAL", receivedTransport.get());
        }

        @Test
        @DisplayName("Should include analyzer ID header when provided")
        void shouldIncludeAnalyzerIdHeader() throws IOException {
            // Create new server context to capture analyzer ID
            AtomicReference<String> receivedAnalyzerId = new AtomicReference<>();

            httpServer.createContext("/api/OpenELIS-Global/analyzer/astm", exchange -> {
                receivedAnalyzerId.set(exchange.getRequestHeaders()
                    .getFirst(SerialMessageHandler.ANALYZER_ID_HEADER));
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("OK".getBytes());
                }
            });

            String message = "H|\\^&|||TEST";
            handler.handleMessage(message, "/dev/ttyUSB0", "ANALYZER-001");

            assertEquals("ANALYZER-001", receivedAnalyzerId.get());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle empty message")
        void shouldHandleEmptyMessage() {
            HandleResult result = handler.handleMessage("", "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertEquals("Empty message", result.message());
        }

        @Test
        @DisplayName("Should handle null message")
        void shouldHandleNullMessage() {
            HandleResult result = handler.handleMessage(null, "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertEquals("Empty message", result.message());
        }

        @Test
        @DisplayName("Should handle HTTP error response")
        void shouldHandleHttpErrorResponse() {
            responseCode = 500;
            responseBody = "Internal Server Error";

            String message = "H|\\^&|||TEST";
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertTrue(result.message().contains("500"));
        }

        @Test
        @DisplayName("Should handle connection refused")
        void shouldHandleConnectionRefused() {
            // Stop the server to simulate connection refused
            httpServer.stop(0);

            String message = "H|\\^&|||TEST";
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertFalse(result.success());
            assertTrue(result.message().contains("Error") || result.message().contains("refused"));
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("Should include basic auth when configured")
        void shouldIncludeBasicAuthWhenConfigured() throws IOException {
            // Set up auth
            httpConfig.setUsername("testuser");
            httpConfig.setPassword("testpass".toCharArray());
            handler = new SerialMessageHandler(httpConfig);

            AtomicReference<String> receivedAuth = new AtomicReference<>();
            httpServer.createContext("/api/OpenELIS-Global/analyzer/astm", exchange -> {
                receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("OK".getBytes());
                }
            });

            String message = "H|\\^&|||TEST";
            handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertNotNull(receivedAuth.get());
            assertTrue(receivedAuth.get().startsWith("Basic "));
        }

        @Test
        @DisplayName("Should not include auth when not configured")
        void shouldNotIncludeAuthWhenNotConfigured() throws IOException {
            AtomicReference<String> receivedAuth = new AtomicReference<>();
            httpServer.createContext("/api/OpenELIS-Global/analyzer/astm", exchange -> {
                receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("OK".getBytes());
                }
            });

            String message = "H|\\^&|||TEST";
            handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertNull(receivedAuth.get());
        }
    }

    @Nested
    @DisplayName("Complex Message Tests")
    class ComplexMessageTests {

        @Test
        @DisplayName("Should handle complete ASTM result message")
        void shouldHandleCompleteASTMResultMessage() {
            String astmMessage =
                "H|\\^&|||MINDRAY^BC-5380^12345|||||||P|1|20260205120000\r" +
                "P|1||PAT001||DOE^JOHN||19800101|M\r" +
                "O|1|SAM001||^^^CBC|R||20260205120000\r" +
                "R|1|^^^WBC|7.5|10^3/uL|4.0-11.0|N||F|||20260205120000\r" +
                "R|2|^^^RBC|4.8|10^6/uL|4.5-5.5|N||F|||20260205120000\r" +
                "R|3|^^^HGB|14.2|g/dL|12.0-16.0|N||F|||20260205120000\r" +
                "R|4|^^^HCT|42.5|%|37.0-47.0|N||F|||20260205120000\r" +
                "R|5|^^^PLT|250|10^3/uL|150-400|N||F|||20260205120000\r" +
                "L|1|N";

            HandleResult result = handler.handleMessage(astmMessage, "/dev/ttyUSB0", "MINDRAY-001");

            assertTrue(result.success());
            assertEquals(astmMessage, receivedBody.get());
        }

        @Test
        @DisplayName("Should handle complete HL7 ORU message")
        void shouldHandleCompleteHL7ORUMessage() {
            String hl7Message =
                "MSH|^~\\&|ANALYZER|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                "PID|1||PAT001||DOE^JOHN||19800101|M|||123 MAIN ST^^CITY^ST^12345\r" +
                "PV1|1|O|LAB|||||||||||||||V001\r" +
                "ORC|RE|ORD001|SAM001|||||||20260205120000|||DR^SMITH\r" +
                "OBR|1|ORD001|SAM001|CBC^COMPLETE BLOOD COUNT|||20260205110000\r" +
                "OBX|1|NM|WBC^WHITE BLOOD CELL||7.5|10^3/uL|4.0-11.0|N|||F\r" +
                "OBX|2|NM|RBC^RED BLOOD CELL||4.8|10^6/uL|4.5-5.5|N|||F\r" +
                "OBX|3|NM|HGB^HEMOGLOBIN||14.2|g/dL|12.0-16.0|N|||F";

            HandleResult result = handler.handleMessage(hl7Message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals("/api/OpenELIS-Global/analyzer/hl7", receivedPath.get());
            assertEquals(hl7Message, receivedBody.get());
        }

        @Test
        @DisplayName("Should handle message with special characters")
        void shouldHandleMessageWithSpecialCharacters() {
            String message = "H|\\^&|||TEST^with\\special|characters~here\r" +
                            "P|1||ID&with^special|chars\r" +
                            "L|1|N";

            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals(message, receivedBody.get());
        }

        @Test
        @DisplayName("Should handle large message")
        void shouldHandleLargeMessage() {
            // Build a large message with many result segments
            StringBuilder sb = new StringBuilder();
            sb.append("H|\\^&|||ANALYZER|||||||P|1|20260205120000\r");
            sb.append("P|1||PATIENT001\r");

            for (int i = 1; i <= 100; i++) {
                sb.append(String.format("R|%d|^^^TEST%d|%d.%d|units|1-100|N||F\r",
                    i, i, i * 10, i));
            }
            sb.append("L|1|N");

            String message = sb.toString();
            HandleResult result = handler.handleMessage(message, "/dev/ttyUSB0", null);

            assertTrue(result.success());
            assertEquals(message, receivedBody.get());
        }
    }
}
