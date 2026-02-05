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
 * and creates a MessageEnvelope for downstream processing by the message normalizer.
 * </p>
 * <p>
 * Protocol detection:
 * <ul>
 *   <li>application/hl7-v2 or x-hl7-v2 → HL7</li>
 *   <li>text/csv → CSV</li>
 *   <li>text/plain or other → auto-detect from message content</li>
 * </ul>
 * </p>
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
     * @param request the HTTP servlet request (for extracting remote address)
     * @return ResponseEntity with envelope details or error message
     */
    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<InputResponse> receiveAnalyzerMessage(
            @RequestBody(required = false) String requestBody,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            HttpServletRequest request) {

        log.debug("Received HTTP input request");
        log.trace("Content-Type: {}", contentType);
        log.trace("X-Forwarded-For: {}", xForwardedFor);

        // Validate request body
        if (requestBody == null || requestBody.trim().isEmpty()) {
            log.warn("Received empty request body");
            return ResponseEntity.badRequest()
                    .body(new InputResponse(false, "Request body is required", null, null, null));
        }

        // Extract source IP
        String sourceIp = extractSourceIp(xForwardedFor, request);
        log.debug("Source IP: {}", sourceIp);

        // Detect protocol
        Protocol protocol = detectProtocol(contentType, requestBody);
        log.debug("Detected protocol: {}", protocol);

        // Reject UNKNOWN protocol
        if (protocol == Protocol.UNKNOWN) {
            log.warn("Unable to detect protocol for message from {}", sourceIp);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new InputResponse(false, "Unable to detect message protocol", sourceIp, null, null));
        }

        // Create MessageEnvelope
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(protocol)
                .transport(Transport.HTTP)
                .sourceId(sourceIp)
                .rawMessage(requestBody)
                .build();

        log.info("Created MessageEnvelope: protocol={}, transport={}, sourceId={}",
                envelope.getProtocol(), envelope.getTransport(), envelope.getSourceId());

        // TODO: In M7, this will be forwarded to the MessageNormalizer for routing
        // For now, we just return success with envelope details

        return ResponseEntity.ok(new InputResponse(
                true,
                "Message received successfully",
                sourceIp,
                protocol.name(),
                envelope.getReceivedAt().toString()));
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
     * Detects the protocol from Content-Type header or message content.
     * <p>
     * Content-Type hints:
     * <ul>
     *   <li>application/hl7-v2, x-application/hl7-v2 → HL7</li>
     *   <li>text/csv → CSV</li>
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
     */
    public record InputResponse(
            boolean success,
            String message,
            String sourceIp,
            String protocol,
            String receivedAt) {
    }
}
