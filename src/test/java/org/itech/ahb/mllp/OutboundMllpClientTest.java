package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link OutboundMllpClient}. Spins up a small in-process MLLP
 * server thread per test that records the inbound frame and returns a canned
 * ACK, so we can exercise positive/negative/missing-ACK paths without
 * depending on the mock analyzer container.
 */
@DisplayName("OutboundMllpClient — bridge → analyzer MLLP send")
class OutboundMllpClientTest {

    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;
    private static final byte CR = 0x0D;

    private static final String SAMPLE_HL7 =
            "MSH|^~\\&|OE2|LAB|MINDRAY|BC-5380|20260519121500||ORM^O01|CTRL-001|P|2.3.1\r"
            + "PID|1||PAT-99^^^HOSPITAL||DOE^JOHN||19800101|M\r"
            + "ORC|NW|ACC-1|ACC-1\r"
            + "OBR|1|ACC-1|ACC-1|^^^CBC^Complete Blood Count\r";

    private FakeMllpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeMllpServer();
        port = server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
    }

    @Test
    @Timeout(5)
    @DisplayName("positive MSA|AA| ACK → success on first attempt")
    void positiveAckSucceeds() {
        server.setResponseAck("MSH|^~\\&|MINDRAY|BC-5380|OE2|LAB|...||ACK^O01|ACK-1|P|2.3.1\rMSA|AA|CTRL-001\r");

        OutboundMllpClient client = new OutboundMllpClient();
        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", port, SAMPLE_HL7, 2000, 1);

        assertTrue(r.success);
        assertNull(r.error);
        assertNotNull(r.ackMessage);
        assertTrue(r.ackMessage.contains("MSA|AA|"));
        assertEquals(SAMPLE_HL7, server.getReceivedMessage(),
                "Server should have received the exact HL7 payload between VT and FS");
    }

    @Test
    @Timeout(5)
    @DisplayName("negative MSA|AE| ACK → failure")
    void applicationErrorAckFails() {
        server.setResponseAck("MSH|^~\\&|MINDRAY|BC-5380|OE2|LAB|...||ACK^O01|ACK-1|P|2.3.1\rMSA|AE|CTRL-001|Parser error\r");

        OutboundMllpClient client = new OutboundMllpClient();
        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", port, SAMPLE_HL7, 2000, 1);

        assertFalse(r.success);
        assertNotNull(r.error);
        assertTrue(r.error.contains("AE"), "Error should mention application-error code");
    }

    @Test
    @Timeout(5)
    @DisplayName("reject MSA|AR| ACK → failure")
    void applicationRejectAckFails() {
        server.setResponseAck("MSH|^~\\&|MINDRAY|BC-5380|OE2|LAB|...||ACK^O01|ACK-1|P|2.3.1\rMSA|AR|CTRL-001|Reject\r");

        OutboundMllpClient client = new OutboundMllpClient();
        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", port, SAMPLE_HL7, 2000, 1);

        assertFalse(r.success);
        assertTrue(r.error.contains("AR"));
    }

    @Test
    @Timeout(5)
    @DisplayName("server closes connection before FS+CR terminator → failure with clean error")
    void serverClosesBeforeTerminator() {
        server.setCloseEarlyWithoutAck(true);

        OutboundMllpClient client = new OutboundMllpClient();
        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", port, SAMPLE_HL7, 2000, 1);

        assertFalse(r.success);
        assertNotNull(r.error);
        assertTrue(r.error.toLowerCase().contains("closed") || r.error.toLowerCase().contains("terminator"),
                "Error should mention connection close / missing terminator; got: " + r.error);
    }

    @Test
    @Timeout(5)
    @DisplayName("connection refused on closed port → failure, does not throw")
    void connectionRefused() {
        OutboundMllpClient client = new OutboundMllpClient();
        // Use a port we just closed to guarantee connection refused
        int closedPort = port;
        try { server.stop(); } catch (IOException ignored) {}

        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", closedPort, SAMPLE_HL7, 1000, 1);

        assertFalse(r.success);
        assertNotNull(r.error);
    }

    @Test
    @Timeout(5)
    @DisplayName("ACK with MSA but no recognized code → failure")
    void unrecognizedAckCodeFails() {
        server.setResponseAck("MSH|^~\\&|MINDRAY|BC-5380|OE2|LAB|...||ACK^O01|ACK-1|P|2.3.1\rMSA|XX|CTRL-001\r");

        OutboundMllpClient client = new OutboundMllpClient();
        OutboundMllpClient.SendResult r = client.attemptSend("127.0.0.1", port, SAMPLE_HL7, 2000, 1);

        assertFalse(r.success);
        assertTrue(r.error.contains("MSA"));
    }

    /** Threaded fake server that accepts one MLLP frame, captures the body, and replies. */
    private static final class FakeMllpServer {
        private final AtomicReference<String> response = new AtomicReference<>("");
        private final AtomicReference<String> received = new AtomicReference<>("");
        private final AtomicInteger connectionCount = new AtomicInteger(0);
        private volatile boolean closeEarlyWithoutAck = false;
        private ServerSocket serverSocket;
        private Thread acceptThread;

        int start() throws IOException {
            serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            acceptThread = new Thread(this::acceptLoop, "fake-mllp-server-" + port);
            acceptThread.setDaemon(true);
            acceptThread.start();
            return port;
        }

        void stop() throws IOException {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }

        void setResponseAck(String ack) { response.set(ack); }
        void setCloseEarlyWithoutAck(boolean v) { closeEarlyWithoutAck = v; }
        String getReceivedMessage() { return received.get(); }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try (Socket client = serverSocket.accept()) {
                    connectionCount.incrementAndGet();
                    handleClient(client);
                } catch (IOException e) {
                    // ServerSocket closed — normal exit
                    return;
                }
            }
        }

        private void handleClient(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            boolean inFrame = false;
            int prev = -1;
            while ((b = in.read()) != -1) {
                if (!inFrame) {
                    if (b == VT) inFrame = true;
                    continue;
                }
                if (prev == FS && b == CR) {
                    byte[] body = buf.toByteArray();
                    int len = body.length;
                    if (len > 0 && body[len - 1] == FS) len -= 1;
                    received.set(new String(body, 0, len, StandardCharsets.UTF_8));
                    break;
                }
                buf.write(b);
                prev = b;
            }

            if (closeEarlyWithoutAck) {
                client.close();
                return;
            }

            String ack = response.get();
            out.write(VT);
            out.write(ack.getBytes(StandardCharsets.UTF_8));
            out.write(FS);
            out.write(CR);
            out.flush();
        }
    }
}
