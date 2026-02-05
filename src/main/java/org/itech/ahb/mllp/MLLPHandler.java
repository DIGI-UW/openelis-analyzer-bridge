package org.itech.ahb.mllp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;

/**
 * Handler for processing HL7 messages received via MLLP transport.
 * <p>
 * This handler:
 * <ul>
 *   <li>Creates a MessageEnvelope for internal routing</li>
 *   <li>Forwards the raw HL7 message to the configured HTTP endpoint</li>
 *   <li>Generates HL7 ACK/NAK responses based on processing result</li>
 * </ul>
 * </p>
 * <p>
 * HTTP forwarding includes headers:
 * <ul>
 *   <li>X-Analyzer-Id: Extracted from MSH segment or source IP</li>
 *   <li>X-Source-Protocol: HL7</li>
 *   <li>X-Source-Transport: MLLP</li>
 *   <li>X-Source-Id: IP:port of the source</li>
 * </ul>
 * </p>
 */
@Slf4j
public class MLLPHandler {

    /** HTTP header for analyzer identification */
    public static final String HEADER_ANALYZER_ID = "X-Analyzer-Id";

    /** HTTP header for source protocol */
    public static final String HEADER_SOURCE_PROTOCOL = "X-Source-Protocol";

    /** HTTP header for source transport */
    public static final String HEADER_SOURCE_TRANSPORT = "X-Source-Transport";

    /** HTTP header for source identifier (IP:port or similar) */
    public static final String HEADER_SOURCE_ID = "X-Source-Id";

    /** HTTP header for source analyzer IP (for backward compatibility with ASTM) */
    public static final String HEADER_SOURCE_ANALYZER_IP = "X-Source-Analyzer-IP";

    private final URI forwardingUri;
    private final String username;
    private final char[] password;
    private final HttpClient httpClient;

    /**
     * Constructs a new MLLPHandler with the specified forwarding URI.
     *
     * @param forwardingUri the URI to forward HL7 messages to
     */
    public MLLPHandler(URI forwardingUri) {
        this(forwardingUri, null, null);
    }

    /**
     * Constructs a new MLLPHandler with the specified forwarding URI and credentials.
     *
     * @param forwardingUri the URI to forward HL7 messages to
     * @param username the username for HTTP Basic authentication (may be null)
     * @param password the password for HTTP Basic authentication (may be null)
     */
    public MLLPHandler(URI forwardingUri, String username, char[] password) {
        this.forwardingUri = forwardingUri;
        this.username = username;
        this.password = password != null ? password : new char[0];
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Handles an incoming HL7 message.
     * <p>
     * Creates a MessageEnvelope, forwards the message to the HTTP endpoint,
     * and returns an appropriate ACK or NAK response.
     * </p>
     *
     * @param hl7Message the raw HL7 message
     * @param sourceIp the source IP address
     * @return an HL7 ACK or NAK response message
     */
    public String handleMessage(String hl7Message, String sourceIp) {
        Instant receivedAt = Instant.now();
        String analyzerId = extractAnalyzerId(hl7Message, sourceIp);

        // Create MessageEnvelope for internal tracking/routing
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(Protocol.HL7)
            .transport(Transport.MLLP)
            .sourceId(sourceIp)
            .rawMessage(hl7Message)
            .receivedAt(receivedAt)
            .analyzerId(analyzerId)
            .build();

        log.debug("Processing HL7 message: {}", envelope);

        // Forward to HTTP endpoint
        boolean success = forwardToHttp(hl7Message, sourceIp, analyzerId);

        // Generate and return ACK/NAK response
        return generateResponse(hl7Message, success);
    }

    /**
     * Forwards the HL7 message to the configured HTTP endpoint.
     *
     * @param hl7Message the raw HL7 message
     * @param sourceIp the source IP address
     * @param analyzerId the analyzer identifier
     * @return true if forwarding succeeded, false otherwise
     */
    private boolean forwardToHttp(String hl7Message, String sourceIp, String analyzerId) {
        log.debug("Forwarding HL7 message to {}", forwardingUri);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(forwardingUri)
            .header("Content-Type", "text/plain")
            .header(HEADER_SOURCE_PROTOCOL, Protocol.HL7.name())
            .header(HEADER_SOURCE_TRANSPORT, Transport.MLLP.name())
            .header(HEADER_SOURCE_ID, sourceIp)
            .header(HEADER_SOURCE_ANALYZER_IP, sourceIp)
            .POST(HttpRequest.BodyPublishers.ofString(hl7Message));

        if (analyzerId != null && !analyzerId.isEmpty()) {
            requestBuilder.header(HEADER_ANALYZER_ID, analyzerId);
        }

        // Add Basic auth if credentials are configured
        if (username != null && !username.isEmpty()) {
            String auth = username + ":" + new String(password);
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            requestBuilder.header("Authorization", "Basic " + encodedAuth);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();
            log.debug("HTTP forward response: {} {}", statusCode, response.body());

            if (statusCode >= 200 && statusCode < 300) {
                log.info("Successfully forwarded HL7 message from {} to {}", sourceIp, forwardingUri);
                return true;
            } else {
                log.error("HTTP forward failed with status {}: {}", statusCode, response.body());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Error forwarding HL7 message to {}", forwardingUri, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Extracts the analyzer identifier from the HL7 message.
     * <p>
     * Tries to extract from MSH-3 (Sending Application) or MSH-4 (Sending Facility).
     * Falls back to the source IP if not available.
     * </p>
     *
     * @param hl7Message the raw HL7 message
     * @param sourceIp the source IP as fallback
     * @return the analyzer identifier
     */
    String extractAnalyzerId(String hl7Message, String sourceIp) {
        // Try to extract from MSH segment
        // MSH format: MSH|^~\&|SendingApp|SendingFacility|...
        String[] segments = hl7Message.split("\\r|\\n");
        for (String segment : segments) {
            if (segment.startsWith("MSH|") || segment.startsWith("MSH^")) {
                // Find the field separator (typically |)
                char fieldSep = segment.charAt(3);
                String[] fields = segment.split("\\" + fieldSep);

                // MSH-3 is Sending Application (index 2)
                if (fields.length > 2 && fields[2] != null && !fields[2].isEmpty()) {
                    String sendingApp = fields[2];
                    // If MSH-4 (Sending Facility) is also present, combine them
                    if (fields.length > 3 && fields[3] != null && !fields[3].isEmpty()) {
                        return sendingApp + "-" + fields[3];
                    }
                    return sendingApp;
                }
            }
        }

        // Fallback to source IP
        log.debug("Could not extract analyzer ID from MSH, using source IP: {}", sourceIp);
        return sourceIp;
    }

    /**
     * Generates an HL7 ACK or NAK response message.
     *
     * @param originalMessage the original HL7 message
     * @param success true for ACK, false for NAK
     * @return the response message
     */
    String generateResponse(String originalMessage, boolean success) {
        // Extract message control ID from MSH-10
        String messageControlId = extractMessageControlId(originalMessage);
        String sendingApp = "BRIDGE";
        String sendingFacility = "OPENELIS";

        // Simple ACK/NAK generation
        // In a production system, you would use HAPI for proper HL7 message construction
        StringBuilder response = new StringBuilder();
        response.append("MSH|^~\\&|")
                .append(sendingApp).append("|")
                .append(sendingFacility).append("|")
                .append("||||ACK||P|2.5.1\r");
        response.append("MSA|")
                .append(success ? "AA" : "AE").append("|")
                .append(messageControlId != null ? messageControlId : "").append("|")
                .append(success ? "Message received successfully" : "Error processing message")
                .append("\r");

        return response.toString();
    }

    /**
     * Extracts the message control ID from MSH-10.
     *
     * @param hl7Message the HL7 message
     * @return the message control ID, or null if not found
     */
    private String extractMessageControlId(String hl7Message) {
        String[] segments = hl7Message.split("\\r|\\n");
        for (String segment : segments) {
            if (segment.startsWith("MSH|") || segment.startsWith("MSH^")) {
                char fieldSep = segment.charAt(3);
                String[] fields = segment.split("\\" + fieldSep);
                // MSH-10 is Message Control ID (index 9 in 0-based array)
                if (fields.length > 9) {
                    return fields[9];
                }
            }
        }
        return null;
    }
}
