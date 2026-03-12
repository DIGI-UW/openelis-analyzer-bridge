package org.itech.ahb.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.util.ProtocolDetector;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP input endpoint for analyzers with REST API capabilities.
 * <p>
 * This controller accepts raw analyzer messages (ASTM, HL7, or CSV) over HTTP POST
 * and routes them via {@link org.itech.ahb.normalizer.MessageNormalizer} to OpenELIS.
 * </p>
 * <p>
 * Protocol detection:
 * <ul>
 *   <li>application/hl7-v2 or x-application/hl7-v2 → HL7</li>
 *   <li>text/csv or application/csv → CSV</li>
 *   <li>application/x-astm or text containing "astm" → ASTM</li>
 *   <li>text/plain or other → auto-detect from message content</li>
 * </ul>
 * </p>
 * <p>
 * Security note: This endpoint accepts any Content-Type to accommodate diverse analyzer
 * implementations. Authentication and rate limiting should be configured at the
 * infrastructure level (API gateway, firewall). IP headers (X-Forwarded-For, X-Real-IP)
 * are trusted for logging; do not use for security decisions without proxy validation.
 * </p>
 * <p>
 * Part of M7: Message Normalizer milestone — all transport handlers delegate to
 * the normalizer for unified routing logic, retry/backoff, and audit logging.
 * </p>
 *
 * @see org.itech.ahb.normalizer.MessageNormalizer
 * @see MessageEnvelope
 */
@RestController
@RequestMapping("/input")
@Slf4j
public class AnalyzerInputController {

    /**
     * Content-Type for HL7 v2 messages (application/hl7-v2 or vendor variations).
     */
    private static final String CONTENT_TYPE_HL7_V2 = "application/hl7-v2";
    private static final String CONTENT_TYPE_HL7_V2_ALT = "x-application/hl7-v2";

    private final org.itech.ahb.normalizer.MessageNormalizer normalizer;

    /**
     * Constructs a new AnalyzerInputController.
     *
     * @param normalizer the message normalizer for routing
     */
    public AnalyzerInputController(org.itech.ahb.normalizer.MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Receives analyzer messages over HTTP POST.
     * <p>
     * The endpoint auto-detects the protocol from Content-Type header or message content,
     * extracts the source IP from the request, and creates a MessageEnvelope for processing.
     * </p>
     *
     * @param requestBody the raw message content (ASTM, HL7, or CSV)
     * @param contentType the Content-Type header (optional, used for protocol hints)
     * @param xForwardedFor the X-Forwarded-For header (optional, for proxy scenarios)
     * @param xForwardedPort the X-Forwarded-Port header (optional, for proxy scenarios)
     * @param request the HTTP servlet request (for extracting remote address and port)
     * @return ResponseEntity with envelope details or error message
     */
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InputResponse> receiveAnalyzerMessage(
            @RequestBody(required = false) String requestBody,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            @RequestHeader(value = "X-Forwarded-Port", required = false) String xForwardedPort,
            HttpServletRequest request) {

        log.debug("Received HTTP input request");
        log.trace("Content-Type: {}", contentType);
        log.trace("X-Forwarded-For: {}", xForwardedFor);

        // Extract source IP early for inclusion in all responses
        String sourceIp = extractSourceIp(xForwardedFor, request);
        log.debug("Source IP: {}", sourceIp);

        // Validate request body
        if (requestBody == null || requestBody.trim().isEmpty()) {
            log.warn("Received empty request body from {}", sourceIp);
            return ResponseEntity.badRequest()
                    .body(new InputResponse(false, "Request body is required", sourceIp, null, null));
        }

        try {
            // Detect protocol
            Protocol protocol = detectProtocol(contentType, requestBody);
            log.debug("Detected protocol: {}", protocol);

            if (protocol == Protocol.UNKNOWN) {
                log.warn("Unable to detect protocol for message from {}, routing as raw", sourceIp);
            }

            // Extract source port: prefer X-Forwarded-Port when request is proxied,
            // fall back to the TCP remote port for direct connections.
            Integer sourcePort = extractSourcePort(xForwardedFor, xForwardedPort, request);

            // Create MessageEnvelope
            MessageEnvelope envelope = MessageEnvelope.builder()
                    .protocol(protocol)
                    .transport(Transport.HTTP)
                    .sourceId(sourceIp)
                    .sourcePort(sourcePort)
                    .rawMessage(requestBody)
                    .build();

            log.info("Created MessageEnvelope: protocol={}, transport={}, sourceId={}",
                    envelope.getProtocol(), envelope.getTransport(), envelope.getSourceId());

            // Route via MessageNormalizer
            boolean success = normalizer.process(envelope);

            if (!success) {
                log.error("Failed to route {} message from {}", protocol, sourceIp);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new InputResponse(false, "Message routing failed", sourceIp, protocol.name(), null));
            }

            return ResponseEntity.ok(new InputResponse(
                    true,
                    "Message routed successfully",
                    sourceIp,
                    protocol.name(),
                    envelope.getReceivedAt().toString()));
        } catch (RuntimeException e) {
            log.error("Failed to process analyzer message from {}", sourceIp, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new InputResponse(false, "Invalid or malformed analyzer message", sourceIp, null, null));
        }
    }

    /**
     * Extracts the source IP address from the HTTP request.
     * <p>
     * Priority:
     * <ol>
     *   <li>X-Forwarded-For header (first IP in chain, for proxied requests)</li>
     *   <li>X-Real-IP header (common proxy header)</li>
     *   <li>Remote address from the servlet request</li>
     * </ol>
     * </p>
     * <p>
     * Note: These headers can be spoofed. Use for logging only, not security decisions.
     * </p>
     *
     * @param xForwardedFor the X-Forwarded-For header value
     * @param request the HTTP servlet request
     * @return the extracted source IP address
     */
    String extractSourceIp(String xForwardedFor, HttpServletRequest request) {
        // Try X-Forwarded-For header first (handles proxy chains)
        if (xForwardedFor != null && !xForwardedFor.trim().isEmpty()) {
            // X-Forwarded-For may contain multiple IPs: "client, proxy1, proxy2"
            // The first IP is the original client
            String[] ips = xForwardedFor.split(",");
            String clientIp = ips[0].trim();
            if (!clientIp.isEmpty()) {
                return clientIp;
            }
        }

        // Try X-Real-IP header (common in nginx)
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.trim().isEmpty()) {
            return xRealIp.trim();
        }

        // Fall back to remote address
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    /**
     * Extracts the source port for the originating client.
     * <p>
     * Priority:
     * <ol>
     *   <li>{@code X-Forwarded-Port} header — used when the request is forwarded by a proxy
     *       (i.e. {@code X-Forwarded-For} is present). The proxy's TCP port is not meaningful
     *       for analyzer identification, so {@code null} is returned when proxied and no port
     *       header is present.</li>
     *   <li>{@code request.getRemotePort()} — used for direct (non-proxied) connections where
     *       the TCP remote port reliably reflects the analyzer's port.</li>
     * </ol>
     * </p>
     *
     * @param xForwardedFor the X-Forwarded-For header value (non-null means request is proxied)
     * @param xForwardedPort the X-Forwarded-Port header value (original client port set by proxy)
     * @param request the HTTP servlet request
     * @return the source port, or {@code null} if proxied and no port header is available
     */
    Integer extractSourcePort(String xForwardedFor, String xForwardedPort, HttpServletRequest request) {
        boolean isProxied = xForwardedFor != null && !xForwardedFor.trim().isEmpty();

        if (isProxied) {
            // When proxied, request.getRemotePort() reflects the proxy's TCP port, not the
            // original client's port. Use X-Forwarded-Port if available; otherwise unknown.
            if (xForwardedPort != null && !xForwardedPort.trim().isEmpty()) {
                try {
                    return Integer.parseInt(xForwardedPort.trim());
                } catch (NumberFormatException e) {
                    log.warn("Invalid X-Forwarded-Port value: {}", xForwardedPort);
                }
            }
            return null;
        }

        // Direct connection: the TCP remote port is the analyzer's port
        return request.getRemotePort();
    }

    /**
     * Detects the protocol from Content-Type header or message content.
     * <p>
     * Content-Type hints:
     * <ul>
     *   <li>application/hl7-v2, x-application/hl7-v2 → HL7</li>
     *   <li>text/csv, application/csv → CSV</li>
     *   <li>application/x-astm, text/astm → ASTM</li>
     *   <li>text/plain, other, or missing → auto-detect from content</li>
     * </ul>
     * </p>
     *
     * @param contentType the Content-Type header value
     * @param messageBody the message content for auto-detection
     * @return the detected Protocol
     */
    Protocol detectProtocol(String contentType, String messageBody) {
        if (contentType != null) {
            String ct = contentType.toLowerCase().trim();

            // Extract base content type (before any parameters like charset)
            int semicolonIndex = ct.indexOf(';');
            if (semicolonIndex > 0) {
                ct = ct.substring(0, semicolonIndex).trim();
            }

            // Check for HL7 content type
            if (ct.equals(CONTENT_TYPE_HL7_V2) || ct.equals(CONTENT_TYPE_HL7_V2_ALT)
                    || ct.contains("hl7")) {
                return Protocol.HL7;
            }

            // Check for CSV content type
            if (ct.equals("text/csv") || ct.equals("application/csv")) {
                return Protocol.CSV;
            }

            // Check for ASTM content type (non-standard but possible)
            if (ct.contains("astm")) {
                return Protocol.ASTM;
            }
        }

        // Auto-detect from message content
        return ProtocolDetector.detect(messageBody);
    }

    /**
     * Response DTO for the input endpoint.
     *
     * @param success whether the message was accepted
     * @param message human-readable status message
     * @param sourceIp the detected source IP address
     * @param protocol the detected protocol (ASTM, HL7, CSV)
     * @param receivedAt timestamp when the message was received
     */
    public record InputResponse(
            boolean success,
            String message,
            String sourceIp,
            String protocol,
            String receivedAt) {

        /**
         * Returns the processing status for the request.
         *
         * @return "ACCEPTED" if message was validated and queued, "REJECTED" otherwise
         */
        public String status() {
            return success ? "ACCEPTED" : "REJECTED";
        }
    }
}
