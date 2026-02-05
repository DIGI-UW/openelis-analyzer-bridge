package org.itech.ahb.serial;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.config.properties.SerialConfigurationProperties;
import org.itech.ahb.config.properties.SerialConfigurationProperties.Parity;
import org.itech.ahb.config.properties.SerialConfigurationProperties.ProtocolMode;
import org.itech.ahb.config.properties.SerialConfigurationProperties.SerialPortConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Integration tests for serial port communication.
 * <p>
 * These tests require either:
 * <ul>
 *   <li>Physical serial ports (for hardware testing)</li>
 *   <li>Virtual serial ports via socat (for CI/CD)</li>
 *   <li>Docker environment with virtual serial port setup</li>
 * </ul>
 * </p>
 * <p>
 * To run with virtual serial ports:
 * <pre>
 * # Create virtual serial port pair:
 * socat -d -d pty,raw,echo=0,link=/tmp/vserial0 pty,raw,echo=0,link=/tmp/vserial1 &
 *
 * # Set environment variable:
 * export SERIAL_TEST_PORT=/tmp/vserial0
 * export SERIAL_TEST_PORT_PAIR=/tmp/vserial1
 *
 * # Run tests:
 * mvn test -Dtest=SerialIntegrationTest
 * </pre>
 * </p>
 * <p>
 * For Docker-based testing, use docker-compose-serial-test.yml
 * </p>
 */
class SerialIntegrationTest {

    // Environment variables for test configuration
    private static final String SERIAL_PORT_ENV = "SERIAL_TEST_PORT";
    private static final String SERIAL_PORT_PAIR_ENV = "SERIAL_TEST_PORT_PAIR";

    private HttpServer httpServer;
    private int serverPort;
    private List<ReceivedMessage> receivedMessages;
    private CountDownLatch messageLatch;

    @BeforeEach
    void setUp() throws IOException {
        receivedMessages = new ArrayList<>();
        messageLatch = new CountDownLatch(1);

        // Start embedded HTTP server to receive forwarded messages
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        // Set up handler to capture messages
        httpServer.createContext("/api/OpenELIS-Global/analyzer/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String sourceId = exchange.getRequestHeaders().getFirst(SerialMessageHandler.SOURCE_ID_HEADER);
            String transport = exchange.getRequestHeaders().getFirst(SerialMessageHandler.TRANSPORT_HEADER);

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            receivedMessages.add(new ReceivedMessage(path, body, contentType, sourceId, transport));
            messageLatch.countDown();

            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });

        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * Tests that require virtual serial ports created via socat.
     * <p>
     * These tests are skipped unless SERIAL_TEST_PORT environment variable is set.
     * </p>
     */
    @Nested
    @DisplayName("Virtual Serial Port Tests")
    @EnabledIfEnvironmentVariable(named = SERIAL_PORT_ENV, matches = ".+")
    class VirtualSerialPortTests {

        private String testPort;
        private String testPortPair;
        private SerialPortListener listener;

        @BeforeEach
        void setUp() {
            testPort = System.getenv(SERIAL_PORT_ENV);
            testPortPair = System.getenv(SERIAL_PORT_PAIR_ENV);
        }

        @AfterEach
        void tearDown() {
            if (listener != null) {
                listener.stop();
            }
        }

        @Test
        @DisplayName("Should receive ASTM message via virtual serial port")
        void shouldReceiveASTMViaVirtualSerialPort() throws Exception {
            assumeTrue(testPort != null, "SERIAL_TEST_PORT not set");
            assumeTrue(testPortPair != null, "SERIAL_TEST_PORT_PAIR not set");

            // Configure the listener
            SerialConfigurationProperties config = new SerialConfigurationProperties();
            config.setEnabled(true);

            SerialPortConfig portConfig = new SerialPortConfig();
            portConfig.setPath(testPort);
            portConfig.setBaudRate(9600);
            portConfig.setDataBits(8);
            portConfig.setStopBits(1);
            portConfig.setParity(Parity.NONE);
            portConfig.setProtocol(ProtocolMode.ASTM);
            portConfig.setAnalyzerId("TEST-ANALYZER");

            config.getPorts().add(portConfig);

            // Create HTTP config
            HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
            httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort));

            // Create and start listener
            SerialMessageHandler handler = new SerialMessageHandler(httpConfig);
            listener = new SerialPortListener(config, handler);
            listener.start();

            // Wait for port to open
            Thread.sleep(1000);

            // Send ASTM message via the paired port
            sendASTMMessage(testPortPair);

            // Wait for message to be received
            boolean received = messageLatch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Message should be received within timeout");

            // Verify message
            assertEquals(1, receivedMessages.size());
            ReceivedMessage msg = receivedMessages.get(0);
            assertEquals("/api/OpenELIS-Global/analyzer/astm", msg.path);
            assertEquals("SERIAL", msg.transport);
            assertEquals(testPort, msg.sourceId);
            assertTrue(msg.body.contains("H|\\^&"));
        }

        @Test
        @DisplayName("Should receive HL7 message via virtual serial port")
        void shouldReceiveHL7ViaVirtualSerialPort() throws Exception {
            assumeTrue(testPort != null, "SERIAL_TEST_PORT not set");
            assumeTrue(testPortPair != null, "SERIAL_TEST_PORT_PAIR not set");

            // Configure for HL7
            SerialConfigurationProperties config = new SerialConfigurationProperties();
            config.setEnabled(true);

            SerialPortConfig portConfig = new SerialPortConfig();
            portConfig.setPath(testPort);
            portConfig.setBaudRate(9600);
            portConfig.setProtocol(ProtocolMode.HL7);

            config.getPorts().add(portConfig);

            HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
            httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort));

            SerialMessageHandler handler = new SerialMessageHandler(httpConfig);
            listener = new SerialPortListener(config, handler);
            listener.start();

            Thread.sleep(1000);

            // Send HL7 message
            sendHL7Message(testPortPair);

            boolean received = messageLatch.await(10, TimeUnit.SECONDS);
            assertTrue(received, "Message should be received within timeout");

            assertEquals(1, receivedMessages.size());
            ReceivedMessage msg = receivedMessages.get(0);
            assertEquals("/api/OpenELIS-Global/analyzer/hl7", msg.path);
            assertTrue(msg.body.startsWith("MSH|"));
        }

        @Test
        @DisplayName("Should auto-detect protocol via virtual serial port")
        void shouldAutoDetectProtocol() throws Exception {
            assumeTrue(testPort != null, "SERIAL_TEST_PORT not set");
            assumeTrue(testPortPair != null, "SERIAL_TEST_PORT_PAIR not set");

            SerialConfigurationProperties config = new SerialConfigurationProperties();
            config.setEnabled(true);

            SerialPortConfig portConfig = new SerialPortConfig();
            portConfig.setPath(testPort);
            portConfig.setBaudRate(9600);
            portConfig.setProtocol(ProtocolMode.AUTO);

            config.getPorts().add(portConfig);

            HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
            httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort));

            SerialMessageHandler handler = new SerialMessageHandler(httpConfig);
            listener = new SerialPortListener(config, handler);
            listener.start();

            Thread.sleep(1000);

            // Send ASTM message - should auto-detect
            sendASTMMessage(testPortPair);

            boolean received = messageLatch.await(10, TimeUnit.SECONDS);
            assertTrue(received);

            ReceivedMessage msg = receivedMessages.get(0);
            assertEquals("/api/OpenELIS-Global/analyzer/astm", msg.path);
        }

        /**
         * Sends an ASTM message via a serial port.
         */
        private void sendASTMMessage(String portPath) throws Exception {
            com.fazecast.jSerialComm.SerialPort port = com.fazecast.jSerialComm.SerialPort.getCommPort(portPath);
            port.setBaudRate(9600);
            port.setComPortTimeouts(com.fazecast.jSerialComm.SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 1000);

            if (!port.openPort()) {
                throw new RuntimeException("Could not open port: " + portPath);
            }

            try {
                // ASTM protocol: ENQ
                port.writeBytes(new byte[]{0x05}, 1);
                Thread.sleep(100);

                // Wait for ACK (in real test, should read this)
                Thread.sleep(100);

                // Send frame: <STX><FN><data><ETX><checksum><CR><LF>
                String text = "H|\\^&|||TEST|||||||P|1";
                byte[] frame = buildASTMFrame(1, text, true);
                port.writeBytes(frame, frame.length);
                Thread.sleep(100);

                // Send EOT
                port.writeBytes(new byte[]{0x04}, 1);

            } finally {
                port.closePort();
            }
        }

        /**
         * Sends an HL7 message via a serial port using MLLP framing.
         */
        private void sendHL7Message(String portPath) throws Exception {
            com.fazecast.jSerialComm.SerialPort port = com.fazecast.jSerialComm.SerialPort.getCommPort(portPath);
            port.setBaudRate(9600);
            port.setComPortTimeouts(com.fazecast.jSerialComm.SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 1000);

            if (!port.openPort()) {
                throw new RuntimeException("Could not open port: " + portPath);
            }

            try {
                String hl7 = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                            "PID|1||12345\r" +
                            "OBX|1|NM|WBC||7.5|10^3/uL";

                // MLLP: <VT>message<FS><CR>
                byte[] message = new byte[hl7.length() + 3];
                message[0] = 0x0B;  // VT
                System.arraycopy(hl7.getBytes(), 0, message, 1, hl7.length());
                message[hl7.length() + 1] = 0x1C;  // FS
                message[hl7.length() + 2] = 0x0D;  // CR

                port.writeBytes(message, message.length);
            } finally {
                port.closePort();
            }
        }

        private byte[] buildASTMFrame(int frameNumber, String text, boolean isFinal) {
            byte terminator = isFinal ? (byte) 0x03 : (byte) 0x17;
            int checksum = 0;
            checksum += (byte) Character.forDigit(frameNumber % 8, 10);
            for (byte b : text.getBytes()) {
                checksum += b;
            }
            checksum += terminator;
            checksum %= 256;
            String checksumStr = String.format("%02X", checksum);

            byte[] frame = new byte[1 + 1 + text.length() + 1 + 2 + 2];
            int pos = 0;
            frame[pos++] = 0x02;  // STX
            frame[pos++] = (byte) Character.forDigit(frameNumber % 8, 10);
            System.arraycopy(text.getBytes(), 0, frame, pos, text.length());
            pos += text.length();
            frame[pos++] = terminator;
            frame[pos++] = (byte) checksumStr.charAt(0);
            frame[pos++] = (byte) checksumStr.charAt(1);
            frame[pos++] = 0x0D;  // CR
            frame[pos] = 0x0A;    // LF
            return frame;
        }
    }

    /**
     * Tests for port listing and status (no actual serial ports required).
     */
    @Nested
    @DisplayName("Port Status Tests")
    class PortStatusTests {

        @Test
        @DisplayName("Should report correct status for non-existent port")
        void shouldReportStatusForNonExistentPort() {
            SerialConfigurationProperties config = new SerialConfigurationProperties();
            config.setEnabled(true);

            SerialPortConfig portConfig = new SerialPortConfig();
            portConfig.setPath("/dev/nonexistent_port_xyz123");
            portConfig.setBaudRate(9600);

            // Use setPorts instead of getPorts().add() due to defensive copy
            List<SerialPortConfig> ports = new ArrayList<>();
            ports.add(portConfig);
            config.setPorts(ports);

            HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
            httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort));

            SerialMessageHandler handler = new SerialMessageHandler(httpConfig);
            SerialPortListener listener = new SerialPortListener(config, handler);

            // Start should not throw
            assertDoesNotThrow(listener::start);

            // Should be pending reconnection (port doesn't exist)
            SerialPortListener.PortStatus status = listener.getPortStatus("/dev/nonexistent_port_xyz123");
            assertFalse(status.isOpen());
            assertTrue(status.isPendingReconnect() || status.reconnectAttempts() > 0);

            listener.stop();
        }

        @Test
        @DisplayName("Should list no open ports when none configured")
        void shouldListNoOpenPortsWhenNoneConfigured() {
            SerialConfigurationProperties config = new SerialConfigurationProperties();
            config.setEnabled(true);

            HTTPForwardServerConfigurationProperties httpConfig = new HTTPForwardServerConfigurationProperties();
            httpConfig.setUri(java.net.URI.create("http://localhost:" + serverPort));

            SerialMessageHandler handler = new SerialMessageHandler(httpConfig);
            SerialPortListener listener = new SerialPortListener(config, handler);
            listener.start();

            assertTrue(listener.getOpenPorts().isEmpty());

            listener.stop();
        }
    }

    /**
     * Record for capturing received messages.
     */
    private record ReceivedMessage(
        String path,
        String body,
        String contentType,
        String sourceId,
        String transport
    ) {}
}
