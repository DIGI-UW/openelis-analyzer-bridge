package org.itech.ahb.serial;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.util.ProtocolDetector;
import org.springframework.stereotype.Service;

/**
 * Handles complete messages received from serial ports.
 * <p>
 * This service:
 * <ul>
 *   <li>Detects the message protocol (ASTM, HL7, CSV)</li>
 *   <li>Creates a MessageEnvelope with serial transport metadata</li>
 *   <li>Forwards the message to the appropriate OpenELIS endpoint</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class SerialMessageHandler {

    /** HTTP header for source analyzer identification */
    public static final String SOURCE_ID_HEADER = "X-Source-Analyzer-IP";

    /** HTTP header for transport type */
    public static final String TRANSPORT_HEADER = "X-Message-Transport";

    /** HTTP header for analyzer ID (optional) */
    public static final String ANALYZER_ID_HEADER = "X-Analyzer-ID";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final HttpClient httpClient;

    /**
     * Creates a new SerialMessageHandler.
     *
     * @param httpConfig configuration for the HTTP forward server
     */
    public SerialMessageHandler(HTTPForwardServerConfigurationProperties httpConfig) {
        this.httpConfig = httpConfig;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    }

    /**
     * Handles a complete message received from a serial port.
     *
     * @param message the complete message content
     * @param serialPortPath the serial port path (e.g., /dev/ttyUSB0)
     * @param analyzerId optional analyzer ID from configuration
     * @return the result of handling the message
     */
    public HandleResult handleMessage(String message, String serialPortPath, String analyzerId) {
        if (message == null || message.isEmpty()) {
            log.warn("Received empty message from serial port {}", serialPortPath);
            return new HandleResult(false, "Empty message");
        }

        // Detect protocol
        Protocol protocol = ProtocolDetector.detect(message);
        log.info("Received {} message from serial port {} ({} bytes)",
            protocol, serialPortPath, message.length());

        // Create message envelope
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(protocol)
            .transport(Transport.SERIAL)
            .sourceId(serialPortPath)
            .rawMessage(message)
            .analyzerId(analyzerId)
            .build();

        // Route to appropriate endpoint
        return forwardMessage(envelope);
    }

    /**
     * Forwards a message envelope to the appropriate OpenELIS endpoint.
     *
     * @param envelope the message envelope
     * @return the result of forwarding
     */
    private HandleResult forwardMessage(MessageEnvelope envelope) {
        URI targetUri = determineTargetUri(envelope.getProtocol());
        String contentType = determineContentType(envelope.getProtocol());

        log.debug("Forwarding {} message to {}", envelope.getProtocol(), targetUri);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(targetUri)
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", contentType)
            .header(SOURCE_ID_HEADER, envelope.getSourceId())
            .header(TRANSPORT_HEADER, Transport.SERIAL.name())
            .POST(HttpRequest.BodyPublishers.ofString(envelope.getRawMessage()));

        // Add analyzer ID header if available
        if (envelope.getAnalyzerId() != null && !envelope.getAnalyzerId().isEmpty()) {
            requestBuilder.header(ANALYZER_ID_HEADER, envelope.getAnalyzerId());
        }

        // Add authentication if configured
        if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
            String auth = httpConfig.getUsername() + ":" + new String(httpConfig.getPassword());
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            requestBuilder.header("Authorization", "Basic " + encodedAuth);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Successfully forwarded {} message to OpenELIS (status {})",
                    envelope.getProtocol(), response.statusCode());
                return new HandleResult(true, response.body());
            } else {
                log.warn("Failed to forward message: HTTP {} - {}",
                    response.statusCode(), response.body());
                return new HandleResult(false,
                    "HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            log.error("IO error forwarding message to {}: {}", targetUri, e.getMessage());
            return new HandleResult(false, "IO Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while forwarding message to {}", targetUri);
            return new HandleResult(false, "Interrupted");
        }
    }

    /**
     * Determines the target URI based on the protocol.
     */
    private URI determineTargetUri(Protocol protocol) {
        String basePath = httpConfig.getUri().toString();
        // Remove trailing slash if present
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }

        // Route to protocol-specific endpoint
        String endpoint = switch (protocol) {
            case ASTM -> "/api/OpenELIS-Global/analyzer/astm";
            case HL7 -> "/api/OpenELIS-Global/analyzer/hl7";
            case CSV -> "/api/OpenELIS-Global/analyzer/csv";
            case UNKNOWN -> "/api/OpenELIS-Global/analyzer/raw";
        };

        // Handle base URI that might already include a path
        URI baseUri = httpConfig.getUri();
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        int port = baseUri.getPort();

        String portPart = port > 0 ? ":" + port : "";
        return URI.create(scheme + "://" + host + portPart + endpoint);
    }

    /**
     * Determines the Content-Type header based on the protocol.
     */
    private String determineContentType(Protocol protocol) {
        return switch (protocol) {
            case CSV -> "text/csv";
            case HL7 -> "application/hl7-v2";
            default -> "text/plain";
        };
    }

    /**
     * Result of handling a message.
     */
    public record HandleResult(boolean success, String message) {
    }
}
