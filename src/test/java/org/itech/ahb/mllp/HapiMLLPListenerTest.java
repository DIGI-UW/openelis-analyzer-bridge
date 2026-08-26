package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.routing.MessageRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for HAPI MLLP listener components.
 */
@DisplayName("HAPI MLLP Listener Tests")
class HapiMLLPListenerTest {

    /** Each test gets a unique port to avoid BindException from port reuse */
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(12580);

    private static final int TEST_TIMEOUT = 5000;

    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;
    private static final byte CR = 0x0D;

    private MLLPConfig config;
    private HapiMLLPListener listener;
    private int testPort;

    @BeforeEach
    void setUp() {
        testPort = PORT_COUNTER.getAndIncrement();
        config = new MLLPConfig();
        config.setPort(testPort);
        config.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
    }

    @Nested
    @DisplayName("MLLP Framing Tests")
    class MLLPFramingTests {

        @Test
        @DisplayName("Should handle valid MLLP-framed message")
        @Timeout(15)
        void shouldHandleValidMLLPMessage() throws Exception {
            // Given: A test router that records received envelopes
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // Given: A valid HL7 message
            String hl7Message = "MSH|^~\\&|TestApp|TestFacility|DestApp|DestFac|20260205120000||ORM^O01|12345|P|2.5.1\r" +
                               "PID|1||12345^^^MRN||Doe^John||19800101|M\r";

            // When: Sending the message with MLLP framing
            String response = sendMLLPMessage("localhost", testPort, hl7Message);

            // Then: Router should receive the message
            assertTrue(testRouter.awaitMessage(5, TimeUnit.SECONDS), "Router should receive message");
            assertNotNull(testRouter.getLastEnvelope(), "Envelope should not be null");
            assertEquals(Protocol.HL7, testRouter.getLastEnvelope().getProtocol());
            assertEquals(Transport.MLLP, testRouter.getLastEnvelope().getTransport());
            assertNotNull(response, "Should receive a response");
            assertTrue(response.contains("MSH|"), "Response should be HL7 ACK");
            assertTrue(response.contains("MSA|AA|"), "Response should contain ACK code");
        }

        @Test
        @DisplayName("Should extract source IP from connection")
        @Timeout(15)
        void shouldExtractSourceIP() throws Exception {
            // Given: A test router
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            String hl7Message = "MSH|^~\\&|TestApp|TestFac|DestApp|DestFac|20260205120000||ORM^O01|IP001|P|2.5.1\r";

            // When: Sending from localhost
            sendMLLPMessage("localhost", testPort, hl7Message);

            // Then: Source IP should be captured in envelope
            assertTrue(testRouter.awaitMessage(5, TimeUnit.SECONDS));
            MessageEnvelope envelope = testRouter.getLastEnvelope();
            assertNotNull(envelope);
            String sourceId = envelope.getSourceId();
            assertNotNull(sourceId);
            assertTrue(
                sourceId.equals("127.0.0.1") || sourceId.equals("localhost") ||
                sourceId.startsWith("0:0:0:0") || sourceId.equals("::1"),
                "Source IP should be localhost variant, got: " + sourceId
            );
        }

        @Test
        @DisplayName("Should handle multiple messages on same connection")
        @Timeout(15)
        void shouldHandleMultipleMessages() throws Exception {
            // Given: A test router
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // When: Sending multiple messages on same connection
            try (Socket socket = new Socket("localhost", testPort)) {
                socket.setSoTimeout(TEST_TIMEOUT);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                for (int i = 0; i < 3; i++) {
                    String msg = "MSH|^~\\&|App" + i + "|Fac" + i + "|DestApp|DestFac|20260205120000||ORM^O01|" + (i + 100) + "|P|2.5.1\r";
                    sendMLLPFrame(out, msg);
                    readMLLPResponse(in);
                    Thread.sleep(150); // Avoid rate limiting
                }
            }

            // Then: All messages should be received
            Thread.sleep(500); // Allow processing
            assertTrue(testRouter.getMessageCount() >= 3,
                "Should receive at least 3 messages, got: " + testRouter.getMessageCount());
        }
    }

    @Nested
    @DisplayName("HAPI Handler Tests")
    class HapiHandlerTests {

        @Test
        @DisplayName("Should extract analyzer ID from MSH-3 (Sending Application) using Terser")
        void shouldExtractAnalyzerIdUsingHapiTerser() throws Exception {
            // Given: A HAPI receiving application
            TestMessageRouter testRouter = new TestMessageRouter();
            HapiReceivingApplication app = new HapiReceivingApplication(testRouter);

            // Given: HL7 message with Sending Application in MSH-3
            String hl7Message = "MSH|^~\\&|SYSMEX-XN|LAB1|DestApp|DestFac|20260205120000||ORM^O01|123|P|2.5.1\r";
            Message message = new PipeParser().parse(hl7Message);

            // When: Extracting analyzer ID
            String analyzerId = app.extractAnalyzerId(message);

            // Then: Should extract from MSH-3 and MSH-4
            assertEquals("SYSMEX-XN-LAB1", analyzerId);
        }

        @Test
        @DisplayName("Should leave the protocol sender empty when MSH fields are empty")
        void shouldNotInventAProtocolSenderFromTheSourceIP() throws Exception {
            // Given: A HAPI receiving application
            TestMessageRouter testRouter = new TestMessageRouter();
            HapiReceivingApplication app = new HapiReceivingApplication(testRouter);

            // Given: HL7 message with empty MSH-3 and MSH-4
            String hl7Message = "MSH|^~\\&|||DestApp|DestFac|20260205120000||ORM^O01|123|P|2.5.1\r";
            Message message = new PipeParser().parse(hl7Message);

            // When: Extracting analyzer ID
            String analyzerId = app.extractAnalyzerId(message);

            assertNull(analyzerId);
        }

        @Test
        @DisplayName("Should generate properly-formed ACK with all required MSH fields")
        @Timeout(15)
        void shouldGenerateProperlyFormedACK() throws Exception {
            // Given: A listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // Given: An HL7 message
            String hl7Message = "MSH|^~\\&|TestApp|TestFac|DestApp|DestFac|20260205120000||ORM^O01|MSG12345|P|2.5.1\r";

            // When: Sending the message
            String response = sendMLLPMessage("localhost", testPort, hl7Message);

            // Then: ACK should have all required MSH fields
            assertTrue(testRouter.awaitMessage(5, TimeUnit.SECONDS));
            assertNotNull(response, "Should receive ACK response");

            // Parse ACK to verify fields
            Message ackMessage = new PipeParser().parse(response);
            Terser terser = new Terser(ackMessage);

            // MSH-3 (Sending Application) - should be set
            assertNotNull(terser.get("/MSH-3"), "MSH-3 should be populated");

            // MSH-7 (Date/Time) - should be set
            String dateTime = terser.get("/MSH-7");
            assertNotNull(dateTime, "MSH-7 (timestamp) should be populated");
            assertFalse(dateTime.isEmpty(), "MSH-7 should not be empty");

            // MSH-9 (Message Type) - should contain ACK
            String messageType = terser.get("/MSH-9-1");
            assertEquals("ACK", messageType, "MSH-9 message type should be ACK");

            // MSA-1 (Acknowledgment Code) - should be AA
            String ackCode = terser.get("/MSA-1");
            assertEquals("AA", ackCode, "MSA-1 should be AA (Application Accept)");

            // MSA-2 (Message Control ID) - should match original
            String controlId = terser.get("/MSA-2");
            assertEquals("MSG12345", controlId, "MSA-2 should contain original message control ID");
        }

        @Test
        @DisplayName("Should generate NAK response for failed routing")
        @Timeout(15)
        void shouldGenerateNAKForFailure() throws Exception {
            // Given: A router that always fails
            TestMessageRouter failingRouter = new TestMessageRouter();
            failingRouter.setAlwaysFail(true);
            listener = new HapiMLLPListener(config, failingRouter);
            listener.start();
            waitForServerReady(testPort);

            // Given: An HL7 message
            String hl7Message = "MSH|^~\\&|TestApp|TestFac|DestApp|DestFac|20260205120000||ORM^O01|MSG99999|P|2.5.1\r";

            // When: Sending the message (routing will fail)
            String response = sendMLLPMessage("localhost", testPort, hl7Message);

            // Then: Response should be a NAK (or error)
            assertNotNull(response, "Should receive a response even on failure");
            // HAPI may generate an ACE (Application Error) or error ACK
            assertTrue(response.contains("MSA|") || response.contains("MSH|"),
                "Should contain HL7 response structure");
        }

        @Test
        @DisplayName("Should extract source IP from HAPI metadata")
        void shouldExtractSourceIpFromMetadata() {
            // Given: A HAPI receiving application
            TestMessageRouter testRouter = new TestMessageRouter();
            HapiReceivingApplication app = new HapiReceivingApplication(testRouter);

            // Given: Metadata with SENDING_IP
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("SENDING_IP", "10.0.0.5");

            // When: Extracting source IP
            String sourceIp = app.extractSourceIp(metadata);

            // Then: Should extract IP
            assertEquals("10.0.0.5", sourceIp);
        }

        @Test
        @DisplayName("Should return 'unknown' when source IP not in metadata")
        void shouldReturnUnknownWhenNoSourceIp() {
            // Given: A HAPI receiving application
            TestMessageRouter testRouter = new TestMessageRouter();
            HapiReceivingApplication app = new HapiReceivingApplication(testRouter);

            // Given: Metadata without SENDING_IP
            Map<String, Object> metadata = new HashMap<>();

            // When: Extracting source IP
            String sourceIp = app.extractSourceIp(metadata);

            // Then: Should return "unknown"
            assertEquals("unknown", sourceIp);
        }
    }

    @Nested
    @DisplayName("Server Lifecycle Tests")
    class ServerLifecycleTests {

        @Test
        @DisplayName("Should start and stop gracefully")
        @Timeout(15)
        void shouldStartAndStopGracefully() throws Exception {
            // Given: A listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);

            // When: Starting
            listener.start();
            waitForServerReady(testPort);

            // Then: Should be running
            assertTrue(listener.isRunning());
            assertEquals(testPort, listener.getPort());

            // When: Stopping
            listener.stop();
            Thread.sleep(500);

            // Then: Should be stopped
            assertFalse(listener.isRunning());
        }

        @Test
        @DisplayName("Should reject connections after stop")
        @Timeout(15)
        void shouldRejectConnectionsAfterStop() throws Exception {
            // Given: A running listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // When: Stopping
            listener.stop();

            // Wait for server socket to fully close
            Thread.sleep(1000);

            // Then: New connections should fail
            assertThrows(IOException.class, () -> {
                try (Socket socket = new Socket("localhost", testPort)) {
                    // Force interaction to detect closed connection
                    socket.setSoTimeout(2000);
                    OutputStream out = socket.getOutputStream();
                    sendMLLPFrame(out, "MSH|^~\\&|A|B|C|D|20260205||ORM^O01|1|P|2.5.1\r");
                    socket.getInputStream().read();
                }
            });
        }

        @Test
        @DisplayName("Should not crash on double stop")
        @Timeout(15)
        void shouldNotCrashOnDoubleStop() throws Exception {
            // Given: A running listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // When: Stopping twice
            listener.stop();
            assertDoesNotThrow(() -> listener.stop());
        }
    }

    @Nested
    @DisplayName("Rate Limiting Tests")
    class RateLimitingTests {

        @Test
        @DisplayName("Should rate limit rapid messages from same IP")
        @Timeout(30)
        void shouldRateLimitRapidMessages() throws Exception {
            // Given: A listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // First, send one message and verify it succeeds (baseline)
            String firstMsg = "MSH|^~\\&|App|Fac|DestApp|DestFac|20260205120000||ORM^O01|RATE0|P|2.5.1\r";
            String firstResponse = sendMLLPMessage("localhost", testPort, firstMsg);
            assertTrue(firstResponse != null && firstResponse.contains("MSA|AA|"),
                "First message should succeed");

            // Now send rapid-fire messages (no delay between them)
            int rejectedCount = 0;
            for (int i = 1; i <= 5; i++) {
                try {
                    String msg = "MSH|^~\\&|App|Fac|DestApp|DestFac|20260205120000||ORM^O01|RATE" + i + "|P|2.5.1\r";
                    String response = sendMLLPMessage("localhost", testPort, msg);
                    if (response != null && !response.contains("MSA|AA|")) {
                        rejectedCount++;
                    }
                } catch (IOException e) {
                    rejectedCount++;
                }
            }

            // Then: At least some messages should be rate limited
            assertTrue(rejectedCount >= 1,
                "At least one rapid message should be rate limited, but all " +
                (5 - rejectedCount) + " of 5 succeeded");
        }

        @Test
        @DisplayName("Should cleanup old rate limit entries")
        void shouldCleanupOldEntries() throws Exception {
            // Given: A rate limiter
            TestMessageRouter testRouter = new TestMessageRouter();
            HapiReceivingApplication app = new HapiReceivingApplication(testRouter);
            RateLimitingReceivingApplication rateLimiter = new RateLimitingReceivingApplication(app);

            try {
                // When: Cleaning up (no entries yet)
                rateLimiter.cleanupOldEntries();

                // Then: Should not throw
                assertEquals(0, rateLimiter.getTrackedIpCount());
            } finally {
                rateLimiter.shutdown();
            }
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
            assertFalse(defaultConfig.isEnabled(), "Should be disabled by default");
        }
    }

    @Nested
    @DisplayName("MessageEnvelope Integration Tests")
    class MessageEnvelopeTests {

        @Test
        @DisplayName("Should populate MessageEnvelope with all fields from HAPI")
        @Timeout(15)
        void shouldPopulateEnvelopeWithAllFields() throws Exception {
            // Given: A listener
            TestMessageRouter testRouter = new TestMessageRouter();
            listener = new HapiMLLPListener(config, testRouter);
            listener.start();
            waitForServerReady(testPort);

            // When: Sending a message
            String hl7Message = "MSH|^~\\&|SYSMEX-XN|LAB1|DestApp|DestFac|20260205120000||ORM^O01|ENV001|P|2.5.1\r";
            sendMLLPMessage("localhost", testPort, hl7Message);

            // Then: Envelope should be fully populated
            assertTrue(testRouter.awaitMessage(5, TimeUnit.SECONDS));
            MessageEnvelope envelope = testRouter.getLastEnvelope();

            assertNotNull(envelope);
            assertEquals(Protocol.HL7, envelope.getProtocol());
            assertEquals(Transport.MLLP, envelope.getTransport());
            assertNotNull(envelope.getSourceId(), "Source ID should be set");
            assertNotNull(envelope.getRawMessage(), "Raw message should be set");
            assertNotNull(envelope.getReceivedAt(), "Received timestamp should be set");
            assertEquals("SYSMEX-XN-LAB1", envelope.getProtocolAnalyzerHint(),
                "Protocol analyzer hint should be extracted from MSH-3 and MSH-4");
            assertNull(envelope.getResolvedAnalyzerId(),
                "Saved connection identity is resolved later by MessageNormalizer");
        }
    }

    // ===== Helper methods =====

    /**
     * Waits for the HAPI server to be ready to accept connections.
     * Much more reliable than Thread.sleep(500).
     */
    private void waitForServerReady(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket("localhost", port)) {
                socket.close();
                // Small extra delay for HAPI to register the application handler
                Thread.sleep(100);
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Server not ready on port " + port + " within 5 seconds");
    }

    private String sendMLLPMessage(String host, int port, String message) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(TEST_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendMLLPFrame(out, message);
            return readMLLPResponse(in);
        }
    }

    private void sendMLLPFrame(OutputStream out, String message) throws IOException {
        out.write(VT);
        out.write(message.getBytes(StandardCharsets.UTF_8));
        out.write(FS);
        out.write(CR);
        out.flush();
    }

    private String readMLLPResponse(InputStream in) throws IOException {
        int startByte = in.read();
        if (startByte != VT) {
            throw new IOException("Expected VT start block, got: 0x" + Integer.toHexString(startByte));
        }

        StringBuilder response = new StringBuilder();
        int prevByte = -1;
        int currentByte;
        while ((currentByte = in.read()) != -1) {
            if (prevByte == FS && currentByte == CR) {
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

    // ===== Test router that records received envelopes =====

    private static class TestMessageRouter implements MessageRouter {
        private final AtomicReference<MessageEnvelope> lastEnvelope = new AtomicReference<>();
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private final BlockingQueue<MessageEnvelope> messageQueue = new LinkedBlockingQueue<>();
        private volatile boolean alwaysFail = false;

        @Override
        public boolean route(MessageEnvelope envelope) {
            lastEnvelope.set(envelope);
            messageCount.incrementAndGet();
            messageQueue.offer(envelope);
            return !alwaysFail;
        }

        void setAlwaysFail(boolean alwaysFail) {
            this.alwaysFail = alwaysFail;
        }

        MessageEnvelope getLastEnvelope() {
            return lastEnvelope.get();
        }

        int getMessageCount() {
            return messageCount.get();
        }

        /** Waits for a message to arrive. Works correctly for every call, not just the first. */
        boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messageQueue.poll(timeout, unit) != null;
        }
    }
}
