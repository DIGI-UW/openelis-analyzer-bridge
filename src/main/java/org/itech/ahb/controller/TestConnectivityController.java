package org.itech.ahb.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test connectivity to an analyzer from the bridge's network perspective.
 *
 * <p>OE calls this endpoint during test-connection instead of opening direct
 * sockets to analyzer IPs. The bridge is the only service on analyzer networks,
 * so it is the correct place to verify reachability.
 *
 * <p>Supports protocol-aware probes:
 * <ul>
 *   <li>TCP: SYN/ACK only (verifies network reachability)</li>
 *   <li>ASTM: ENQ (0x05) → expects ACK (0x06)</li>
 *   <li>HL7/MLLP: VT-framed ping → expects VT-framed ACK</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class TestConnectivityController {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    // ASTM control characters
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;

    // MLLP framing
    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;
    private static final byte CR = 0x0D;

    @PostMapping("/test-connectivity")
    public ResponseEntity<Map<String, Object>> testConnectivity(@RequestBody ConnectivityRequest request) {
        String transport = request.transport != null ? request.transport.toUpperCase() : "TCP";

        long startTime = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transport", transport);

        try {
            switch (transport) {
            case "FILE":
                if (request.path == null || request.path.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("reachable", false,
                            "error", "path is required for FILE transport"));
                }
                response.put("path", request.path);
                testFileConnectivity(request.path, response);
                break;

            case "SERIAL":
                if (request.path == null || request.path.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("reachable", false,
                            "error", "path is required for SERIAL transport"));
                }
                response.put("path", request.path);
                testSerialConnectivity(request.path, response);
                break;

            default:
                // TCP-based: ASTM, HL7/MLLP, or plain TCP
                if (request.host == null || request.host.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("reachable", false,
                            "error", "host is required for TCP transport"));
                }
                if (request.port == null || request.port < 1 || request.port > 65535) {
                    return ResponseEntity.badRequest().body(Map.of("reachable", false,
                            "error", "valid port is required for TCP transport"));
                }
                response.put("host", request.host);
                response.put("port", request.port);
                String protocol = request.protocol != null ? request.protocol.toUpperCase() : "TCP";
                response.put("protocol", protocol);

                switch (protocol) {
                case "ASTM":
                    testAstmConnectivity(request.host, request.port, response);
                    break;
                case "HL7":
                case "MLLP":
                    testMllpConnectivity(request.host, request.port, response);
                    break;
                default:
                    testTcpConnectivity(request.host, request.port, response);
                    break;
                }
                break;
            }
        } catch (Exception e) {
            response.put("reachable", false);
            response.put("message", "Unexpected error: " + e.getMessage());
            log.error("Test connectivity error: {}", e.getMessage(), e);
        }

        response.put("responseTimeMs", System.currentTimeMillis() - startTime);
        return ResponseEntity.ok(response);
    }

    /**
     * TCP-only: verify SYN/ACK (network reachability).
     */
    private void testTcpConnectivity(String host, int port, Map<String, Object> response) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            response.put("reachable", true);
            response.put("message", "TCP connection succeeded");
            log.info("TCP connectivity test passed for {}:{}", host, port);
        } catch (SocketTimeoutException e) {
            response.put("reachable", false);
            response.put("message", "Connection timeout");
            log.warn("TCP connectivity timeout for {}:{}", host, port);
        } catch (java.net.ConnectException e) {
            response.put("reachable", false);
            response.put("message", "Connection refused");
            log.warn("TCP connectivity refused for {}:{}", host, port);
        } catch (java.net.UnknownHostException e) {
            response.put("reachable", false);
            response.put("message", "Unknown host: " + host);
            log.warn("Unknown host: {}", host);
        } catch (IOException e) {
            response.put("reachable", false);
            response.put("message", "Connection error: " + e.getMessage());
            log.warn("TCP connectivity error for {}:{}: {}", host, port, e.getMessage());
        }
    }

    /**
     * ASTM: TCP connect + ENQ/ACK handshake.
     */
    private void testAstmConnectivity(String host, int port, Map<String, Object> response) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(ENQ);
            out.flush();

            int responseByte = in.read();
            if (responseByte == ACK) {
                response.put("reachable", true);
                response.put("message", "ASTM handshake succeeded (ENQ→ACK)");
                log.info("ASTM connectivity test passed for {}:{}", host, port);
            } else {
                response.put("reachable", false);
                response.put("message", "ASTM handshake failed: expected ACK (0x06), got 0x"
                        + String.format("%02X", responseByte & 0xFF));
                log.warn("ASTM handshake failed for {}:{}: got 0x{}", host, port,
                        String.format("%02X", responseByte & 0xFF));
            }
        } catch (SocketTimeoutException e) {
            response.put("reachable", false);
            response.put("message", "ASTM handshake timeout (no ACK response)");
        } catch (IOException e) {
            response.put("reachable", false);
            response.put("message", "ASTM connection error: " + e.getMessage());
        }
    }

    /**
     * HL7/MLLP: TCP connect + send minimal MLLP frame, expect MLLP ACK.
     */
    private void testMllpConnectivity(String host, int port, Map<String, Object> response) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send a minimal HL7 message wrapped in MLLP framing
            String pingMsg = "MSH|^~\\&|BRIDGE|TEST|||"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "||ACK|PING-" + System.currentTimeMillis() + "|P|2.3.1\r";

            byte[] frame = new byte[1 + pingMsg.getBytes().length + 2];
            frame[0] = VT;
            System.arraycopy(pingMsg.getBytes(), 0, frame, 1, pingMsg.getBytes().length);
            frame[frame.length - 2] = FS;
            frame[frame.length - 1] = CR;

            out.write(frame);
            out.flush();

            // Read MLLP response: expect VT ... FS CR
            byte[] buf = new byte[4096];
            int read = in.read(buf);
            if (read > 0 && buf[0] == VT) {
                response.put("reachable", true);
                response.put("message", "MLLP handshake succeeded (HL7 ACK received)");
                log.info("MLLP connectivity test passed for {}:{}", host, port);
            } else if (read > 0) {
                response.put("reachable", true);
                response.put("message", "TCP connected, response received (non-MLLP)");
            } else {
                response.put("reachable", false);
                response.put("message", "TCP connected but no MLLP response");
            }
        } catch (SocketTimeoutException e) {
            response.put("reachable", false);
            response.put("message", "MLLP handshake timeout (no response)");
        } catch (IOException e) {
            response.put("reachable", false);
            response.put("message", "MLLP connection error: " + e.getMessage());
        }
    }

    /**
     * Test FILE transport: verify the import directory exists and is writable.
     */
    private void testFileConnectivity(String path, Map<String, Object> response) {
        java.io.File dir = new java.io.File(path);
        if (!dir.exists()) {
            response.put("reachable", false);
            response.put("message", "Directory does not exist: " + path);
            log.warn("FILE connectivity: directory not found: {}", path);
        } else if (!dir.isDirectory()) {
            response.put("reachable", false);
            response.put("message", "Path is not a directory: " + path);
            log.warn("FILE connectivity: not a directory: {}", path);
        } else if (!dir.canRead()) {
            response.put("reachable", false);
            response.put("message", "Directory is not readable: " + path);
            log.warn("FILE connectivity: not readable: {}", path);
        } else {
            boolean writable = dir.canWrite();
            response.put("reachable", true);
            response.put("message", "Directory exists and is readable"
                    + (writable ? " (writable)" : " (read-only)"));
            response.put("writable", writable);
            log.info("FILE connectivity: OK for {}", path);
        }
    }

    /**
     * Test SERIAL transport: verify the device path exists.
     */
    private void testSerialConnectivity(String devicePath, Map<String, Object> response) {
        java.io.File device = new java.io.File(devicePath);
        if (!device.exists()) {
            response.put("reachable", false);
            response.put("message", "Serial device not found: " + devicePath);
            log.warn("SERIAL connectivity: device not found: {}", devicePath);
        } else if (!device.canRead()) {
            response.put("reachable", false);
            response.put("message", "Serial device not readable: " + devicePath);
            log.warn("SERIAL connectivity: not readable: {}", devicePath);
        } else {
            response.put("reachable", true);
            response.put("message", "Serial device accessible: " + devicePath);
            log.info("SERIAL connectivity: OK for {}", devicePath);
        }
    }

    public static class ConnectivityRequest {
        /** TCP host (for ASTM/HL7/TCP transports) */
        public String host;
        /** TCP port (for ASTM/HL7/TCP transports) */
        public Integer port;
        /** Transport type: TCP (default), FILE, SERIAL */
        public String transport;
        /** Protocol for TCP transport: TCP (default), ASTM, HL7/MLLP */
        public String protocol;
        /** File/directory path (for FILE/SERIAL transports) */
        public String path;
    }
}
