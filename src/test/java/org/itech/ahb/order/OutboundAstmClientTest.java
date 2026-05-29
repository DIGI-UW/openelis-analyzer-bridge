package org.itech.ahb.order;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * M4: the bridge sends an ASTM order to the analyzer over TCP. Driven against a
 * real in-process ASTM receiver (stands in for the analyzer) so the ENQ/ACK +
 * framing handshake is genuinely exercised, not mocked.
 */
@DisplayName("OutboundAstmClient — ASTM order send over TCP")
class OutboundAstmClientTest {

    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;

    private FakeAstmReceiver receiver;

    @AfterEach
    void tearDown() throws Exception {
        if (receiver != null) receiver.close();
    }

    @Test
    @Timeout(5)
    @DisplayName("sends ENQ → framed records → EOT; receiver gets the records")
    void sendsOrder() throws Exception {
        receiver = new FakeAstmReceiver();
        int port = receiver.start();

        List<String> records = List.of(
                "H|\\^&|||OpenELIS^Order^1.0|||||||LIS2-A2",
                "P|1|||PAT-9",
                "O|1|ACC-1||^^^MTB-RIF|R",
                "L|1|N");

        OutboundAstmClient client = new OutboundAstmClient();
        boolean ok = client.send("127.0.0.1", port, records, 2000);

        assertTrue(ok, "send should succeed against an ACKing receiver");
        List<String> got = receiver.awaitRecords(2000);
        assertEquals(4, got.size(), "receiver should get all 4 records; got: " + got);
        assertEquals("O|1|ACC-1||^^^MTB-RIF|R", got.get(2));
    }

    @Test
    @Timeout(5)
    @DisplayName("instrument asserts proactive ENQ (GeneXpert) → contention resolved, order still delivered")
    void sendsOrderDespiteProactiveEnqContention() throws Exception {
        // A real GeneXpert (and the mock, via proactive_enq) asserts ENQ on
        // connect when it has queued results. The LIS, sending an order, also
        // sends ENQ — line contention (CLSI LIS1-A §8.2.7.1). The LIS holds
        // priority for active orders; the instrument yields and ACKs. The client
        // must resolve this and still deliver the order, not abort on the ENQ.
        receiver = new FakeAstmReceiver(true);
        int port = receiver.start();

        List<String> records = List.of(
                "H|\\^&|||OpenELIS^Order^1.0|||||||LIS2-A2",
                "P|1|||PAT-9",
                "O|1|ACC-1||^^^MTB-RIF|R",
                "L|1|N");

        OutboundAstmClient client = new OutboundAstmClient();
        boolean ok = client.send("127.0.0.1", port, records, 2000);

        assertTrue(ok, "send should resolve ENQ contention (LIS priority) and still deliver the order");
        List<String> got = receiver.awaitRecords(2000);
        assertEquals(4, got.size(), "receiver should get all 4 records despite contention; got: " + got);
        assertEquals("O|1|ACC-1||^^^MTB-RIF|R", got.get(2));
    }

    @Test
    @Timeout(5)
    @DisplayName("connection refused → returns false, does not throw")
    void connectionRefused() {
        OutboundAstmClient client = new OutboundAstmClient();
        // nothing listening on this port
        boolean ok = client.send("127.0.0.1", 1, List.of("H|"), 1000);
        assertFalse(ok);
    }

    /**
     * Minimal ASTM receiver: ACK the ENQ, ACK each frame, collect content, stop on EOT.
     * When {@code proactiveEnq} is set, it asserts ENQ on connect first — modeling a
     * real GeneXpert with queued results — so the LIS order send hits line contention.
     */
    private static final class FakeAstmReceiver {
        private ServerSocket serverSocket;
        private Thread thread;
        private final boolean proactiveEnq;
        private final AtomicReference<List<String>> records = new AtomicReference<>();

        FakeAstmReceiver() {
            this(false);
        }

        FakeAstmReceiver(boolean proactiveEnq) {
            this.proactiveEnq = proactiveEnq;
        }

        int start() throws Exception {
            serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            thread = new Thread(this::run, "fake-astm-receiver");
            thread.setDaemon(true);
            thread.start();
            return port;
        }

        private void run() {
            try (Socket conn = serverSocket.accept()) {
                conn.setSoTimeout(3000);
                InputStream in = conn.getInputStream();
                OutputStream out = conn.getOutputStream();
                if (proactiveEnq) {
                    // Instrument has queued results: assert ENQ on connect. The
                    // read loop below then ACKs the client's contending ENQ.
                    out.write(ENQ);
                    out.flush();
                }
                List<String> recs = new ArrayList<>();
                int b;
                while ((b = in.read()) != -1) {
                    if (b == ENQ) {
                        out.write(ACK);
                        out.flush();
                    } else if (b == EOT) {
                        break;
                    } else if (b == STX) {
                        StringBuilder frame = new StringBuilder();
                        int prev = -1, c;
                        while ((c = in.read()) != -1) {
                            if (prev == CR && c == LF) break;
                            frame.append((char) c);
                            prev = c;
                        }
                        String s = frame.toString();
                        int term = s.indexOf((char) ETX);
                        if (term < 0) term = s.indexOf((char) ETB());
                        // strip leading frame number, trailing ETX+checksum+CR
                        String content = s.substring(1, term < 0 ? s.length() : term);
                        recs.add(content);
                        out.write(ACK);
                        out.flush();
                    }
                }
                records.set(recs);
            } catch (Exception e) {
                records.set(new ArrayList<>());
            }
        }

        private static byte ETB() { return 0x17; }

        List<String> awaitRecords(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (records.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            return records.get() == null ? new ArrayList<>() : records.get();
        }

        void close() throws Exception {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        }
    }
}
