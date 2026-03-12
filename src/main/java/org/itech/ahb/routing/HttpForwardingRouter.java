package org.itech.ahb.routing;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.OpenELISConfig;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>X-Source-Port: From envelope.sourcePort (when available)</li>
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

    /** HTTP header for source port number */
    public static final String HEADER_SOURCE_PORT = "X-Source-Port";

    private static final Pattern IPV4_PATTERN =
        Pattern.compile("^(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_PATTERN =
        Pattern.compile("^[0-9a-fA-F:]+$");

    /** Maximum backoff wait time in milliseconds (1 minute cap to prevent overflow) */
    private static final long MAX_BACKOFF_MS = 60_000;

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final OpenELISConfig.RetryConfig retryConfig;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final HttpClient httpClient;

    /**
     * Constructs a new HttpForwardingRouter with the specified configuration.
     *
     * @param httpConfig the HTTP forwarding server configuration
     * @param openelisConfig the OpenELIS configuration (optional, for retry settings)
     */
    public HttpForwardingRouter(
            HTTPForwardServerConfigurationProperties httpConfig,
            @Autowired(required = false) OpenELISConfig openelisConfig) {
        this.httpConfig = httpConfig;
        this.retryConfig = openelisConfig != null ? openelisConfig.getRetry() : null;
        this.connectTimeoutSeconds = openelisConfig != null
            ? openelisConfig.getConnectTimeoutSeconds()
            : 30;
        this.readTimeoutSeconds = openelisConfig != null
            ? openelisConfig.getReadTimeoutSeconds()
            : 30;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();

        if (retryConfig != null) {
            log.info("HttpForwardingRouter configured with retry: maxAttempts={}, backoffMs={}",
                retryConfig.getMaxAttempts(), retryConfig.getBackoffMs());
        } else {
            log.info("HttpForwardingRouter configured without retry (single attempt)");
        }
        log.info("HttpForwardingRouter timeouts: connect={}s read={}s",
            connectTimeoutSeconds, readTimeoutSeconds);
    }

    /**
     * Routes a message envelope to the appropriate HTTP endpoint.
     * <p>
     * Implements retry with exponential backoff if configured via {@link OpenELISConfig.RetryConfig}.
     * Retries are attempted for:
     * <ul>
     *   <li>5xx server errors (may be transient)</li>
     *   <li>Network/IO errors (connection failures, timeouts)</li>
     * </ul>
     * </p>
     * <p>
     * Non-retryable failures:
     * <ul>
     *   <li>4xx client errors (bad request, authentication failure, etc.)</li>
     *   <li>Thread interruption</li>
     * </ul>
     * </p>
     *
     * @param envelope the message with transport metadata
     * @return true if routing succeeded (HTTP 2xx response), false otherwise
     */
    @Override
    public boolean route(MessageEnvelope envelope) {
        if (envelope == null) {
            log.error("Cannot route null MessageEnvelope");
            return false;
        }
        if (envelope.getProtocol() == null || envelope.getTransport() == null) {
            log.error("MessageEnvelope missing protocol or transport");
            return false;
        }
        if (envelope.getSourceId() == null || envelope.getSourceId().trim().isEmpty()) {
            log.error("MessageEnvelope missing sourceId");
            return false;
        }
        if (envelope.getRawMessage() == null || envelope.getRawMessage().trim().isEmpty()) {
            log.error("MessageEnvelope missing rawMessage");
            return false;
        }

        log.debug("Routing {} message from {} via {}",
            envelope.getProtocol(), envelope.getSourceId(), envelope.getTransport());

        int maxAttempts = retryConfig != null ? retryConfig.getMaxAttempts() : 1;
        long backoffMs = retryConfig != null ? retryConfig.getBackoffMs() : 1000;

        // Determine endpoint based on protocol
        URI targetUri = buildTargetUri(envelope);

        // Retry loop with exponential backoff
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Build HTTP request with envelope metadata as headers
                HttpRequest request = buildRequest(envelope, targetUri);

                // Forward to HTTP endpoint
                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();
                log.debug("HTTP forward response (attempt {}/{}): {}",
                    attempt, maxAttempts, statusCode);

                // Success (2xx)
                if (statusCode >= 200 && statusCode < 300) {
                    if (attempt > 1) {
                        log.info("Successfully routed {} message from {} to {} after {} attempts",
                            envelope.getProtocol(), envelope.getSourceId(), targetUri, attempt);
                    } else {
                        log.info("Successfully routed {} message from {} to {}",
                            envelope.getProtocol(), envelope.getSourceId(), targetUri);
                    }
                    return true;
                }

                // Non-retryable client errors (4xx) — fail immediately
                if (statusCode >= 400 && statusCode < 500) {
                    log.error("Non-retryable HTTP error {}", statusCode);
                    return false;
                }

                // Server errors (5xx) — retry if attempts remaining
                log.warn("Server error {} on attempt {}/{}",
                    statusCode, attempt, maxAttempts);

            } catch (IOException e) {
                log.warn("IO error on attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while routing message");
                return false;
            }

            // Retry with exponential backoff if attempts remaining
            if (attempt < maxAttempts) {
                long uncappedWaitMs = backoffMs * (1L << (attempt - 1)); // exponential: backoff * 2^(attempt-1)
                long waitMs = Math.min(uncappedWaitMs, MAX_BACKOFF_MS); // Cap to prevent overflow
                log.info("Retrying in {}ms (attempt {}/{})", waitMs, attempt + 1, maxAttempts);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted during backoff");
                    return false;
                }
            }
        }

        log.error("All {} attempts failed for {} message from {}",
            maxAttempts, envelope.getProtocol(), envelope.getSourceId());
        return false;
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
            case UNKNOWN -> "/raw";
            default -> {
                log.warn("Unknown protocol {}, using raw endpoint", envelope.getProtocol());
                yield "/raw";
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
            .timeout(Duration.ofSeconds(readTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(envelope.getRawMessage()));

        // Add analyzer ID header if available
        if (envelope.getAnalyzerId() != null && !envelope.getAnalyzerId().isEmpty()) {
            builder.header(HEADER_ANALYZER_ID, envelope.getAnalyzerId());
        }

        // Add source port header if available
        if (envelope.getSourcePort() != null) {
            builder.header(HEADER_SOURCE_PORT, String.valueOf(envelope.getSourcePort()));
        }

        // Backward compatibility: only set when sourceId looks like an IP address
        if (isIpAddress(envelope.getSourceId())) {
            builder.header(HEADER_SOURCE_ANALYZER_IP, envelope.getSourceId());
        }

        // Add Basic authentication if configured
        if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
            addBasicAuth(builder, httpConfig.getUsername(), httpConfig.getPassword());
        }

        return builder.build();
    }

    private boolean isIpAddress(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.contains(":") && IPV6_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Adds HTTP Basic authentication to the request.
     * <p>
     * Uses CharsetEncoder to convert the password char[] directly to bytes
     * without creating an intermediate String (which would be interned in the
     * String pool and cannot be reliably cleared from memory).
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

        // Convert char[] to UTF-8 bytes via CharsetEncoder (avoids String pool exposure)
        byte[] passwordBytes;
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer byteBuffer = encoder.encode(CharBuffer.wrap(password));
            passwordBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(passwordBytes);
        } catch (Exception e) {
            log.error("Failed to encode password bytes", e);
            return;
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
