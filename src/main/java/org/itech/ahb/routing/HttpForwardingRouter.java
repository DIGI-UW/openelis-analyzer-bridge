package org.itech.ahb.routing;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.springframework.stereotype.Component;

/**
 * HTTP forwarding implementation of MessageRouter.
 * <p>
 * Routes messages to protocol-specific HTTP endpoints based on the message protocol:
 * <ul>
 *   <li>HL7 messages → /analyzer/hl7</li>
 *   <li>ASTM messages → /analyzer/astm</li>
 *   <li>CSV messages → /analyzer/csv</li>
 * </ul>
 * </p>
 * <p>
 * Includes envelope metadata as HTTP headers:
 * <ul>
 *   <li>X-Analyzer-Id: From envelope.analyzerId</li>
 *   <li>X-Source-Protocol: From envelope.protocol</li>
 *   <li>X-Source-Transport: From envelope.transport</li>
 *   <li>X-Source-Id: From envelope.sourceId</li>
 *   <li>X-Source-Analyzer-IP: From envelope.sourceId (backward compatibility)</li>
 * </ul>
 * </p>
 * <p>
 * This implementation is lightweight and prepares for M7 normalizer milestone,
 * where protocol transformation and multi-destination routing will be added.
 * </p>
 *
 * @see MessageRouter
 * @see org.itech.ahb.normalizer.MessageEnvelope
 */
@Component
@Slf4j
public class HttpForwardingRouter implements MessageRouter {

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

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final HttpClient httpClient;

    /**
     * Constructs a new HttpForwardingRouter with the specified configuration.
     *
     * @param httpConfig the HTTP forwarding server configuration
     */
    public HttpForwardingRouter(HTTPForwardServerConfigurationProperties httpConfig) {
        this.httpConfig = httpConfig;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Routes a message envelope to the appropriate HTTP endpoint.
     *
     * @param envelope the message with transport metadata
     * @return true if routing succeeded (HTTP 2xx response), false otherwise
     */
    @Override
    public boolean route(MessageEnvelope envelope) {
        log.debug("Routing {} message from {} via {}",
            envelope.getProtocol(), envelope.getSourceId(), envelope.getTransport());

        try {
            // Determine endpoint based on protocol
            URI targetUri = buildTargetUri(envelope);

            // Build HTTP request with envelope metadata as headers
            HttpRequest request = buildRequest(envelope, targetUri);

            // Forward to HTTP endpoint
            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            log.debug("HTTP forward response: {} {}", statusCode, response.body());

            if (statusCode >= 200 && statusCode < 300) {
                log.info("Successfully routed {} message from {} to {}",
                    envelope.getProtocol(), envelope.getSourceId(), targetUri);
                return true;
            } else {
                log.error("HTTP forward failed with status {}: {}", statusCode, response.body());
                return false;
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error routing {} message to HTTP endpoint", envelope.getProtocol(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Builds the target URI based on the message protocol.
     * <p>
     * Routes messages to protocol-specific endpoints:
     * <ul>
     *   <li>HL7 → /analyzer/hl7</li>
     *   <li>ASTM → /analyzer/astm</li>
     *   <li>CSV → /analyzer/csv</li>
     * </ul>
     * </p>
     *
     * @param envelope the message envelope
     * @return the target URI for this message
     */
    private URI buildTargetUri(MessageEnvelope envelope) {
        URI baseUri = httpConfig.getUri();
        String basePath = baseUri.getPath();

        // Normalize base path (remove trailing slash)
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/analyzer";
        } else if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }

        // Determine endpoint based on protocol
        String protocolPath = switch (envelope.getProtocol()) {
            case HL7 -> "/hl7";
            case ASTM -> "/astm";
            case CSV -> "/csv";
            default -> {
                log.warn("Unknown protocol {}, using base path", envelope.getProtocol());
                yield "";
            }
        };

        try {
            return new URI(
                baseUri.getScheme(),
                baseUri.getUserInfo(),
                baseUri.getHost(),
                baseUri.getPort(),
                basePath + protocolPath,
                baseUri.getQuery(),
                baseUri.getFragment()
            );
        } catch (URISyntaxException e) {
            log.error("Failed to build target URI, using base URI", e);
            return baseUri;
        }
    }

    /**
     * Builds an HTTP request with envelope metadata as headers.
     *
     * @param envelope the message envelope
     * @param targetUri the target URI
     * @return the HTTP request
     */
    private HttpRequest buildRequest(MessageEnvelope envelope, URI targetUri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(targetUri)
            .header("Content-Type", "text/plain")
            .header(HEADER_SOURCE_PROTOCOL, envelope.getProtocol().name())
            .header(HEADER_SOURCE_TRANSPORT, envelope.getTransport().name())
            .header(HEADER_SOURCE_ID, envelope.getSourceId())
            .header(HEADER_SOURCE_ANALYZER_IP, envelope.getSourceId())  // Backward compat
            .POST(HttpRequest.BodyPublishers.ofString(envelope.getRawMessage()));

        // Add analyzer ID header if available
        if (envelope.getAnalyzerId() != null && !envelope.getAnalyzerId().isEmpty()) {
            builder.header(HEADER_ANALYZER_ID, envelope.getAnalyzerId());
        }

        // Add Basic authentication if configured
        if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
            addBasicAuth(builder, httpConfig.getUsername(), httpConfig.getPassword());
        }

        return builder.build();
    }

    /**
     * Adds HTTP Basic authentication to the request.
     * <p>
     * FIX: PR Review Comment #4 - Avoid String conversion of password to prevent
     * it from staying in the String pool. Directly convert char[] to bytes and
     * clear sensitive data immediately after use.
     * </p>
     *
     * @param builder the HTTP request builder
     * @param username the username
     * @param password the password (char array for security)
     */
    private void addBasicAuth(HttpRequest.Builder builder, String username, char[] password) {
        if (password == null || password.length == 0) {
            log.warn("Password is null or empty, skipping Basic auth");
            return;
        }

        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] colonBytes = ":".getBytes(StandardCharsets.UTF_8);

        // FIX: Avoid String conversion - convert char[] directly to bytes
        byte[] passwordBytes = new byte[password.length];
        for (int i = 0; i < password.length; i++) {
            passwordBytes[i] = (byte) password[i];  // Direct char→byte without String intermediate
        }

        // Combine username:password
        byte[] authBytes = new byte[usernameBytes.length + colonBytes.length + passwordBytes.length];
        System.arraycopy(usernameBytes, 0, authBytes, 0, usernameBytes.length);
        System.arraycopy(colonBytes, 0, authBytes, usernameBytes.length, colonBytes.length);
        System.arraycopy(passwordBytes, 0, authBytes, usernameBytes.length + colonBytes.length,
            passwordBytes.length);

        // Encode and add header
        String encodedAuth = Base64.getEncoder().encodeToString(authBytes);
        builder.header("Authorization", "Basic " + encodedAuth);

        // Clear sensitive data immediately
        Arrays.fill(passwordBytes, (byte) 0);
        Arrays.fill(authBytes, (byte) 0);
    }
}
