package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for MLLP Server and Handler components.
 */
@DisplayName("MLLP Server Tests")
class MLLPServerTest {

    private static final int TEST_PORT = 12575; // Use non-standard port for tests
    private static final int TEST_TIMEOUT = 5000;

    private MLLPConfig config;
    private TestMLLPHandler testHandler;
    private MLLPServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() {
        config = new MLLPConfig();
        config.setPort(TEST_PORT);
        config.setTimeout(TEST_TIMEOUT);
        config.setEnabled(true);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.join(1000);
        }
    }

    /**
     * Helper to start the server in a background thread.
     */
    private void startServer() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        serverThread = new Thread(() -> {
            startLatch.countDown();
            server.start();
        });
        serverThread.start();
        startLatch.await(1, TimeUnit.SECONDS);
        Thread.sleep(100); // Give server time to bind
    }

    @Nested
    @DisplayName("MLLP Framing Tests")
    class MLLPFramingTests {

        @Test
        @DisplayName("Should handle valid MLLP-framed message")
        @Timeout(10)
        void shouldHandleValidMLLPMessage() throws Exception {
            // Given: A test handler that records received messages
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);
            startServer();

            // Given: A valid HL7 message
            String hl7Message = "MSH|^~\\&|TestApp|TestFacility|||20260205120000||ORM^O01|12345|P|2.5.1\r" +
                               "PID|1||12345^^^MRN||Doe^John||19800101|M\r";

            // When: Sending the message with MLLP framing
            String response = sendMLLPMessage("localhost", TEST_PORT, hl7Message);

            // Then: Handler should receive the message
            assertTrue(testHandler.awaitMessage(5, TimeUnit.SECONDS), "Handler should receive message");
            assertEquals(hl7Message, testHandler.getLastMessage());
            assertNotNull(response, "Should receive a response");
            assertTrue(response.startsWith("MSH|"), "Response should be HL7 ACK");
        }

        @Test
        @DisplayName("Should extract source IP from connection")
        @Timeout(10)
        void shouldExtractSourceIP() throws Exception {
            // Given: A test handler
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);
            startServer();

            String hl7Message = "MSH|^~\\&|TestApp|||||ORM^O01|123|P|2.5.1\r";

            // When: Sending from localhost
            sendMLLPMessage("localhost", TEST_PORT, hl7Message);

            // Then: Source IP should be captured
            assertTrue(testHandler.awaitMessage(5, TimeUnit.SECONDS));
            String sourceIp = testHandler.getLastSourceIp();
            assertNotNull(sourceIp);
            assertTrue(sourceIp.equals("127.0.0.1") || sourceIp.equals("localhost") ||
                      sourceIp.startsWith("0:0:0:0") || sourceIp.equals("::1"),
                      "Source IP should be localhost variant, got: " + sourceIp);
        }

        @Test
        @DisplayName("Should handle multiple messages on same connection")
        @Timeout(10)
        void shouldHandleMultipleMessages() throws Exception {
            // Given: A test handler
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);
            startServer();

            // When: Sending multiple messages on same connection
            try (Socket socket = new Socket("localhost", TEST_PORT)) {
                socket.setSoTimeout(TEST_TIMEOUT);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                for (int i = 0; i < 3; i++) {
                    String msg = "MSH|^~\\&|App" + i + "|||||ORM^O01|" + i + "|P|2.5.1\r";
                    sendMLLPFrame(out, msg);
                    readMLLPResponse(in);
                }
            }

            // Then: All messages should be received
            Thread.sleep(200); // Allow processing
            assertTrue(testHandler.getMessageCount() >= 3,
                "Should receive at least 3 messages, got: " + testHandler.getMessageCount());
        }
    }

    @Nested
    @DisplayName("MLLP Handler Tests")
    class MLLPHandlerTests {

        @Test
        @DisplayName("Should extract analyzer ID from MSH-3 (Sending Application)")
        void shouldExtractAnalyzerIdFromMSH3() {
            // Given: A handler
            MLLPHandler handler = new MLLPHandler(java.net.URI.create("http://localhost:8080"));

            // Given: HL7 message with Sending Application in MSH-3
            String hl7Message = "MSH|^~\\&|SYSMEX-XN|LAB1|||||ORM^O01|123|P|2.5.1\r";

            // When: Extracting analyzer ID
            String analyzerId = handler.extractAnalyzerId(hl7Message, "192.168.1.1");

            // Then: Should extract from MSH-3 and MSH-4
            assertEquals("SYSMEX-XN-LAB1", analyzerId);
        }

        @Test
        @DisplayName("Should fallback to source IP when MSH fields are empty")
        void shouldFallbackToSourceIP() {
            // Given: A handler
            MLLPHandler handler = new MLLPHandler(java.net.URI.create("http://localhost:8080"));

            // Given: HL7 message with empty MSH-3 and MSH-4
            String hl7Message = "MSH|^~\\&||||||ORM^O01|123|P|2.5.1\r";

            // When: Extracting analyzer ID
            String analyzerId = handler.extractAnalyzerId(hl7Message, "192.168.1.50");

            // Then: Should use source IP
            assertEquals("192.168.1.50", analyzerId);
        }

        @Test
        @DisplayName("Should generate ACK response for successful processing")
        void shouldGenerateACKForSuccess() {
            // Given: A handler
            MLLPHandler handler = new MLLPHandler(java.net.URI.create("http://localhost:8080"));

            // Given: An HL7 message with control ID
            String hl7Message = "MSH|^~\\&|TestApp|TestFac|||||ORM^O01|MSG12345|P|2.5.1\r";

            // When: Generating response for success
            String response = handler.generateResponse(hl7Message, true);

            // Then: Should be an ACK with AA acknowledgment code
            assertTrue(response.contains("MSA|AA|"), "Should contain AA (Application Accept)");
            assertTrue(response.contains("MSG12345"), "Should contain original message control ID");
        }

        @Test
        @DisplayName("Should generate NAK response for failed processing")
        void shouldGenerateNAKForFailure() {
            // Given: A handler
            MLLPHandler handler = new MLLPHandler(java.net.URI.create("http://localhost:8080"));

            // Given: An HL7 message
            String hl7Message = "MSH|^~\\&|TestApp|TestFac|||||ORM^O01|MSG99999|P|2.5.1\r";

            // When: Generating response for failure
            String response = handler.generateResponse(hl7Message, false);

            // Then: Should be a NAK with AE acknowledgment code
            assertTrue(response.contains("MSA|AE|"), "Should contain AE (Application Error)");
            assertTrue(response.contains("MSG99999"), "Should contain original message control ID");
        }
    }

    @Nested
    @DisplayName("Server Lifecycle Tests")
    class ServerLifecycleTests {

        @Test
        @DisplayName("Should start and stop gracefully")
        @Timeout(10)
        void shouldStartAndStopGracefully() throws Exception {
            // Given: A server
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);

            // When: Starting server
            startServer();

            // Then: Server should be running
            assertTrue(server.isRunning());
            assertEquals(TEST_PORT, server.getPort());

            // When: Stopping server
            server.stop();
            Thread.sleep(200);

            // Then: Server should be stopped
            assertFalse(server.isRunning());
        }

        @Test
        @DisplayName("Should reject connections after stop")
        @Timeout(10)
        void shouldRejectConnectionsAfterStop() throws Exception {
            // Given: A running server
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);
            startServer();

            // When: Stopping server
            server.stop();
            Thread.sleep(200);

            // Then: New connections should fail
            assertThrows(IOException.class, () -> {
                new Socket("localhost", TEST_PORT);
            });
        }

        @Test
        @DisplayName("Should not start twice")
        @Timeout(10)
        void shouldNotStartTwice() throws Exception {
            // Given: A running server
            testHandler = new TestMLLPHandler();
            server = new MLLPServer(config, testHandler);
            startServer();

            // When: Trying to start again
            Thread secondStart = new Thread(() -> server.start());
            secondStart.start();
            secondStart.join(500);

            // Then: Should still only be one server on the port
            assertTrue(server.isRunning());
        }
    }

    @Nested
    @DisplayName("MLLPConfig Tests")
    class MLLPConfigTests {

        @Test
        @DisplayName("Should have sensible defaults")
        void shouldHaveSensibleDefaults() {
            MLLPConfig defaultConfig = new MLLPConfig();

            assertEquals(2575, defaultConfig.getPort(), "Default port should be 2575");
            assertEquals(30000, defaultConfig.getTimeout(), "Default timeout should be 30000ms");
            assertEquals(1048576, defaultConfig.getMaxMessageSize(), "Default max size should be 1MB");
            assertTrue(defaultConfig.isEnabled(), "Should be enabled by default");
        }
    }

    // Helper methods for MLLP communication

    /**
     * Sends an MLLP-framed message and returns the response.
     */
    private String sendMLLPMessage(String host, int port, String message) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(TEST_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendMLLPFrame(out, message);
            return readMLLPResponse(in);
        }
    }

    /**
     * Writes an MLLP frame to the output stream.
     */
    private void sendMLLPFrame(OutputStream out, String message) throws IOException {
        out.write(MLLPServer.VT);
        out.write(message.getBytes(StandardCharsets.UTF_8));
        out.write(MLLPServer.FS);
        out.write(MLLPServer.CR);
        out.flush();
    }

    /**
     * Reads an MLLP-framed response from the input stream.
     */
    private String readMLLPResponse(InputStream in) throws IOException {
        // Read start block
        int startByte = in.read();
        if (startByte != MLLPServer.VT) {
            throw new IOException("Expected VT start block, got: 0x" + Integer.toHexString(startByte));
        }

        // Read until FS + CR
        StringBuilder response = new StringBuilder();
        int prevByte = -1;
        int currentByte;
        while ((currentByte = in.read()) != -1) {
            if (prevByte == MLLPServer.FS && currentByte == MLLPServer.CR) {
                // Remove trailing FS
                if (response.length() > 0) {
                    response.setLength(response.length() - 1);
                }
                break;
            }
            response.append((char) currentByte);
            prevByte = currentByte;
        }

        return response.toString();
    }

    /**
     * Test handler that records received messages for verification.
     */
    private static class TestMLLPHandler extends MLLPHandler {
        private final AtomicReference<String> lastMessage = new AtomicReference<>();
        private final AtomicReference<String> lastSourceIp = new AtomicReference<>();
        private final CountDownLatch messageLatch = new CountDownLatch(1);
        private int messageCount = 0;

        TestMLLPHandler() {
            super(java.net.URI.create("http://localhost:8080/test"));
        }

        @Override
        public String handleMessage(String hl7Message, String sourceIp) {
            lastMessage.set(hl7Message);
            lastSourceIp.set(sourceIp);
            messageCount++;
            messageLatch.countDown();
            // Generate a simple ACK response
            return generateResponse(hl7Message, true);
        }

        String getLastMessage() {
            return lastMessage.get();
        }

        String getLastSourceIp() {
            return lastSourceIp.get();
        }

        int getMessageCount() {
            return messageCount;
        }

        boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messageLatch.await(timeout, unit);
        }
    }
}
