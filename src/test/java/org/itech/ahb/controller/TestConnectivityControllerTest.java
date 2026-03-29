package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for TestConnectivityController.
 * <p>
 * Tests protocol-aware connectivity probes (TCP, ASTM, MLLP, FILE, SERIAL).
 * Uses real localhost ServerSockets as mock targets since the controller
 * opens real sockets with no injected dependencies.
 * </p>
 */
class TestConnectivityControllerTest {

    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;
    private static final byte CR = 0x0D;
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;

    private TestConnectivityController controller;

    @BeforeEach
    void setUp() {
        controller = new TestConnectivityController();
    }

    @Nested
    @DisplayName("TCP Connectivity")
    class TcpConnectivityTests {

        @Test
        @DisplayName("Should report reachable=true when TCP connection succeeds")
        void testTcpConnectivity_success() throws IOException {
            try (ServerSocket server = new ServerSocket(0)) {
                int port = server.getLocalPort();

                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = "127.0.0.1";
                request.port = port;
                request.transport = "TCP";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(true, response.getBody().get("reachable"));
                assertEquals("TCP connection succeeded", response.getBody().get("message"));
            }
        }

        @Test
        @DisplayName("Should report reachable=false with 'Connection refused' for closed port")
        void testTcpConnectivity_refused() throws IOException {
            // Bind and immediately close to get a port that is definitely not listening
            int closedPort;
            try (ServerSocket server = new ServerSocket(0)) {
                closedPort = server.getLocalPort();
            }

            TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
            request.host = "127.0.0.1";
            request.port = closedPort;
            request.transport = "TCP";

            ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(false, response.getBody().get("reachable"));
            assertEquals("Connection refused", response.getBody().get("message"));
        }

        @Test
        @DisplayName("Should return 400 for invalid port values (0, -1, 99999)")
        void testTcpConnectivity_invalidPort() {
            for (int invalidPort : new int[]{0, -1, 99999}) {
                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = "127.0.0.1";
                request.port = invalidPort;
                request.transport = "TCP";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                        "Expected 400 for port=" + invalidPort);
                assertNotNull(response.getBody());
                assertEquals(false, response.getBody().get("reachable"));
            }
        }

        @Test
        @DisplayName("Should return 400 for missing or blank host")
        void testTcpConnectivity_missingHost() {
            for (String invalidHost : new String[]{null, "", "   "}) {
                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = invalidHost;
                request.port = 9999;
                request.transport = "TCP";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                        "Expected 400 for host=" + (invalidHost == null ? "null" : "'" + invalidHost + "'"));
                assertNotNull(response.getBody());
                assertEquals(false, response.getBody().get("reachable"));
            }
        }
    }

    @Nested
    @DisplayName("ASTM Connectivity")
    class AstmConnectivityTests {

        @Test
        @DisplayName("Should report reachable=true when ASTM ENQ receives ACK")
        void testAstmConnectivity_success() throws Exception {
            try (ServerSocket server = new ServerSocket(0)) {
                int port = server.getLocalPort();

                // Background thread: accept connection, read ENQ, respond ACK
                Thread serverThread = new Thread(() -> {
                    try (Socket client = server.accept()) {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();
                        int b = in.read();
                        if (b == ENQ) {
                            out.write(ACK);
                            out.flush();
                        }
                    } catch (IOException e) {
                        // test will fail via assertion
                    }
                });
                serverThread.setDaemon(true);
                serverThread.start();

                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = "127.0.0.1";
                request.port = port;
                request.transport = "TCP";
                request.protocol = "ASTM";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(true, response.getBody().get("reachable"));
                String message = (String) response.getBody().get("message");
                assertTrue(message.contains("ENQ") && message.contains("ACK"),
                        "Expected message mentioning ENQ and ACK, got: " + message);

                serverThread.join(2000);
            }
        }

        @Test
        @DisplayName("Should report reachable=false when connection closed without ACK")
        void testAstmConnectivity_connectionClosed() throws Exception {
            try (ServerSocket server = new ServerSocket(0)) {
                int port = server.getLocalPort();

                // Background thread: accept then immediately close
                Thread serverThread = new Thread(() -> {
                    try (Socket client = server.accept()) {
                        // close immediately without responding
                    } catch (IOException e) {
                        // expected
                    }
                });
                serverThread.setDaemon(true);
                serverThread.start();

                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = "127.0.0.1";
                request.port = port;
                request.transport = "TCP";
                request.protocol = "ASTM";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(false, response.getBody().get("reachable"));
                String message = (String) response.getBody().get("message");
                assertTrue(message.toLowerCase().contains("connection closed")
                                || message.toLowerCase().contains("error"),
                        "Expected message about connection closed, got: " + message);

                serverThread.join(2000);
            }
        }
    }

    @Nested
    @DisplayName("MLLP Connectivity")
    class MllpConnectivityTests {

        @Test
        @DisplayName("Should report reachable=true when MLLP receives VT-framed ACK")
        void testMllpConnectivity_success() throws Exception {
            try (ServerSocket server = new ServerSocket(0)) {
                int port = server.getLocalPort();

                // Background thread: accept, read VT-framed message, respond with VT-framed ACK
                Thread serverThread = new Thread(() -> {
                    try (Socket client = server.accept()) {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();

                        // Read until FS CR (end of MLLP frame)
                        byte[] buf = new byte[4096];
                        int total = 0;
                        boolean done = false;
                        while (!done && total < buf.length) {
                            int b = in.read();
                            if (b == -1) break;
                            buf[total++] = (byte) b;
                            // Check for FS CR terminator
                            if (total >= 2 && buf[total - 2] == FS && buf[total - 1] == CR) {
                                done = true;
                            }
                        }

                        // Send a VT-framed ACK response
                        String ack = "MSH|^~\\&|TEST|LAB|||20260326||ACK|1|P|2.3.1\rMSA|AA|PING\r";
                        byte[] ackBytes = ack.getBytes(StandardCharsets.UTF_8);
                        byte[] frame = new byte[1 + ackBytes.length + 2];
                        frame[0] = VT;
                        System.arraycopy(ackBytes, 0, frame, 1, ackBytes.length);
                        frame[frame.length - 2] = FS;
                        frame[frame.length - 1] = CR;
                        out.write(frame);
                        out.flush();
                    } catch (IOException e) {
                        // test will fail via assertion
                    }
                });
                serverThread.setDaemon(true);
                serverThread.start();

                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.host = "127.0.0.1";
                request.port = port;
                request.transport = "TCP";
                request.protocol = "MLLP";

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(true, response.getBody().get("reachable"));
                String message = (String) response.getBody().get("message");
                assertTrue(message.toLowerCase().contains("mllp") || message.toLowerCase().contains("ack"),
                        "Expected message about MLLP success, got: " + message);

                serverThread.join(2000);
            }
        }
    }

    @Nested
    @DisplayName("FILE Connectivity")
    class FileConnectivityTests {

        @Test
        @DisplayName("Should report reachable=true for existing directory")
        void testFileConnectivity_existingDir() throws IOException {
            Path tempDir = Files.createTempDirectory("bridge-test-file");
            try {
                TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
                request.transport = "FILE";
                request.path = tempDir.toString();

                ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(true, response.getBody().get("reachable"));
            } finally {
                Files.deleteIfExists(tempDir);
            }
        }

        @Test
        @DisplayName("Should report reachable=false for nonexistent directory")
        void testFileConnectivity_missingDir() {
            TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
            request.transport = "FILE";
            request.path = "/nonexistent/path/that/does/not/exist";

            ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(false, response.getBody().get("reachable"));
            String message = (String) response.getBody().get("message");
            assertTrue(message.contains("does not exist"),
                    "Expected message about directory not existing, got: " + message);
        }
    }

    @Nested
    @DisplayName("SERIAL Connectivity")
    class SerialConnectivityTests {

        @Test
        @DisplayName("Should report reachable=false for nonexistent serial device")
        void testSerialConnectivity_missingDevice() {
            TestConnectivityController.ConnectivityRequest request = new TestConnectivityController.ConnectivityRequest();
            request.transport = "SERIAL";
            request.path = "/dev/ttyNONEXISTENT999";

            ResponseEntity<Map<String, Object>> response = controller.testConnectivity(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(false, response.getBody().get("reachable"));
            String message = (String) response.getBody().get("message");
            assertTrue(message.toLowerCase().contains("not found"),
                    "Expected message about device not found, got: " + message);
        }
    }
}
