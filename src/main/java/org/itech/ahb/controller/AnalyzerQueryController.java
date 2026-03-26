package org.itech.ahb.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bridge-proxied ASTM query endpoint. OE delegates analyzer queries to the
 * bridge — the bridge is on analyzer networks and performs the actual ASTM
 * ENQ/ACK/frame exchange.
 *
 * <p>This moves the direct OE→analyzer socket code that was in
 * {@code AnalyzerQueryServiceImpl.queryAnalyzerASTM()} to the bridge where
 * it belongs.
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class AnalyzerQueryController {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    // ASTM control characters
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> queryAnalyzer(@RequestBody QueryRequest request) {
        if (request.host == null || request.host.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "host is required"));
        }
        if (request.port == null || request.port < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "valid port is required"));
        }

        int timeoutMs = request.timeoutMs != null && request.timeoutMs > 0
                ? request.timeoutMs : 300_000; // default 5 minutes

        Map<String, Object> response = new LinkedHashMap<>();
        List<String> logEntries = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        try {
            List<String> records = executeAstmQuery(
                    request.host, request.port, timeoutMs, logEntries);

            response.put("success", true);
            response.put("records", records);
            response.put("recordCount", records.size());
            log.info("ASTM query to {}:{} returned {} records",
                    request.host, request.port, records.size());

        } catch (SocketTimeoutException e) {
            response.put("success", false);
            response.put("error", "Query timeout: " + e.getMessage());
            logEntries.add("ERROR: " + e.getMessage());
        } catch (IOException e) {
            response.put("success", false);
            response.put("error", "Connection error: " + e.getMessage());
            logEntries.add("ERROR: " + e.getMessage());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getMessage());
            logEntries.add("ERROR: " + e.getMessage());
            log.error("ASTM query failed for {}:{}", request.host, request.port, e);
        }

        response.put("logs", logEntries);
        response.put("responseTimeMs", System.currentTimeMillis() - startTime);
        return ResponseEntity.ok(response);
    }

    /**
     * Execute ASTM query protocol:
     * 1. ENQ → ACK handshake
     * 2. Send header record (query indicator)
     * 3. Send EOT
     * 4. Receive server ENQ → send ACK
     * 5. Receive data frames until EOT
     */
    private List<String> executeAstmQuery(String host, int port, int timeoutMs,
            List<String> logEntries) throws IOException {

        List<String> records = new ArrayList<>();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(timeoutMs);
            logEntries.add("TCP connected to " + host + ":" + port);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Phase 1: ENQ/ACK
            logEntries.add("Sending ENQ");
            out.write(ENQ);
            out.flush();

            int resp = in.read();
            if (resp != ACK) {
                throw new IOException("Expected ACK, got 0x" + String.format("%02X", resp & 0xFF));
            }
            logEntries.add("Received ACK");

            // Phase 2: Send query header
            String headerRecord = "H|\\^&|||OpenELIS^Query^1.0|||||||LIS2-A2";
            logEntries.add("Sending query header");
            sendFrame(out, headerRecord, 1);

            resp = in.read();
            if (resp != ACK) {
                throw new IOException("Header frame not ACKed, got 0x" + String.format("%02X", resp & 0xFF));
            }

            // Send EOT
            out.write(EOT);
            out.flush();
            logEntries.add("Sent EOT, waiting for response");

            // Phase 3: Wait for server ENQ
            resp = in.read();
            if (resp == ENQ) {
                out.write(ACK);
                out.flush();
                logEntries.add("Received server ENQ, sent ACK");
            } else if (resp == -1) {
                logEntries.add("Connection closed by analyzer (no data)");
                return records;
            } else {
                throw new IOException("Expected ENQ from server, got 0x" + String.format("%02X", resp & 0xFF));
            }

            // Phase 4: Receive frames
            while (true) {
                int b = in.read();
                if (b == EOT) {
                    logEntries.add("Received EOT, end of transmission");
                    break;
                }
                if (b != STX) {
                    throw new IOException("Expected STX, got 0x" + String.format("%02X", b & 0xFF));
                }

                // Frame number
                in.read();

                // Data until ETX
                StringBuilder frameData = new StringBuilder();
                while ((b = in.read()) != ETX) {
                    if (b == -1) {
                        throw new IOException("Unexpected end of stream");
                    }
                    frameData.append((char) b);
                }

                // Checksum (2 hex) + CR + LF
                in.read();
                in.read();
                in.read();
                in.read();

                records.add(frameData.toString());
                logEntries.add("Received: " + (frameData.length() > 60
                        ? frameData.substring(0, 60) + "..." : frameData));

                // ACK
                out.write(ACK);
                out.flush();
            }
        }

        return records;
    }

    private void sendFrame(OutputStream out, String content, int frameNumber) throws IOException {
        byte[] fnBytes = String.valueOf(frameNumber).getBytes();
        byte[] contentBytes = content.getBytes("UTF-8");

        int checksum = 0;
        for (byte b : fnBytes) checksum += b & 0xFF;
        for (byte b : contentBytes) checksum += b & 0xFF;
        checksum += ETX;
        checksum %= 256;

        out.write(STX);
        out.write(fnBytes);
        out.write(contentBytes);
        out.write(ETX);
        out.write(String.format("%02X", checksum).getBytes());
        out.write(CR);
        out.write(LF);
        out.flush();
    }

    public static class QueryRequest {
        public String host;
        public Integer port;
        public Integer timeoutMs;
    }
}
