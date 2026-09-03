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
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.config.FhirRoutingConfig;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.fhir.ASTMResultParser;
import org.itech.ahb.fhir.FhirBundleBuilder;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.file.SqliteFileStateStore;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.util.HttpClientFactory;
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
 *   <li>X-Analyzer-Id: From canonical resolved analyzer ID in envelope</li>
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
    private final FhirRoutingConfig fhirConfig;
    private final SqliteFileStateStore stateStore;
    private final AnalyzerRuntimeRegistry registry;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final HttpClient httpClient;

    /**
     * Constructs a new HttpForwardingRouter with the specified configuration.
     *
     * @param httpConfig the HTTP forwarding server configuration
     * @param fhirConfig the FHIR routing configuration (optional)
     * @param stateStore shared SQLite state store (optional — when absent the
     *                   router logs rejections without persisting them, same
     *                   as the pre-B1 behavior)
     * @param registry   the analyzer registry for pinned profile recognition
     */
    public HttpForwardingRouter(
            HTTPForwardServerConfigurationProperties httpConfig,
            @Autowired(required = false) FhirRoutingConfig fhirConfig,
            @Autowired(required = false) SqliteFileStateStore stateStore,
            @Autowired(required = false) AnalyzerRuntimeRegistry registry) {
        this.httpConfig = httpConfig;
        this.fhirConfig = fhirConfig;
        this.stateStore = stateStore;
        this.registry = registry;
        this.connectTimeoutSeconds = httpConfig.getConnectTimeoutSeconds();
        this.readTimeoutSeconds = httpConfig.getReadTimeoutSeconds();
        this.httpClient = HttpClientFactory.create(connectTimeoutSeconds, httpConfig.isInsecureTls(), "forwarding");

        log.info("HttpForwardingRouter configured with retry: maxAttempts={}, backoffMs={}",
            httpConfig.getMaxAttempts(), httpConfig.getBackoffMs());
        log.info("HttpForwardingRouter timeouts: connect={}s read={}s",
            connectTimeoutSeconds, readTimeoutSeconds);
        if (stateStore == null) {
            log.warn("HttpForwardingRouter: no SqliteFileStateStore bean available — "
                + "rejected bundles will be logged only, not persisted. The admin "
                + "/admin/rejected-bundles endpoint will be empty until a state store is wired.");
        }
    }

    /**
     * Routes a message envelope to the appropriate HTTP endpoint.
     * <p>
     * Implements retry with exponential backoff configured via
     * {@link HTTPForwardServerConfigurationProperties}.
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

        // If FHIR routing enabled, transform to FHIR Bundle
        if (fhirConfig != null && fhirConfig.isUseFhir()) {
            return routeAsFhir(envelope);
        }

        int maxAttempts = httpConfig.getMaxAttempts();
        long backoffMs = httpConfig.getBackoffMs();

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
                    recordRejection(envelope, envelope.getRawMessage(), statusCode,
                        "Non-retryable HTTP " + statusCode);
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
        recordRejection(envelope, envelope.getRawMessage(), 0,
            "All " + maxAttempts + " attempts failed (5xx / IO)");
        return false;
    }

    /**
     * Route a message as a FHIR R4 transaction Bundle.
     *
     * <p>Parses the raw message using the protocol-specific parser, builds a FHIR
     * Bundle, and POSTs to OE's {@code /analyzer/fhir} endpoint.
     */
    private boolean routeAsFhir(MessageEnvelope envelope) {
        var registeredAnalyzer = registry == null || envelope.getSourceId() == null
                ? java.util.Optional.<AnalyzerRuntimeRegistry.AnalyzerEntry>empty()
                : registry.findAnalyzerEntry(envelope.getSourceId());
        if (registeredAnalyzer.isEmpty() ||
                registeredAnalyzer.get().getControlResultRecognition() == null) {
            String reason = "FHIR routing requires control-result recognition from a pinned profile";
            log.error("{} for analyzer source {}", reason, envelope.getSourceId());
            recordRejection(envelope, envelope.getRawMessage(), 0, reason);
            return false;
        }

        ControlResultRecognition profileRecognition =
                registeredAnalyzer.get().getControlResultRecognition();
        if ((envelope.getProtocol() == Protocol.ASTM || envelope.getProtocol() == Protocol.CSV)
                && registeredAnalyzer.get().getAstmResultRecordSelection() == null) {
            String reason = "FHIR routing requires ASTM result-record selection from a pinned profile";
            log.error("{} for analyzer source {}", reason, envelope.getSourceId());
            recordRejection(envelope, envelope.getRawMessage(), 0, reason);
            return false;
        }
        HL7ResultParser.ParsedResults parsed = switch (envelope.getProtocol()) {
            case HL7 -> {
                String raw = envelope.getRawMessage();
                if (raw == null || raw.isBlank()) yield null;
                String normalized = raw.replace("\r\n", "\r").replace("\n", "\r");
                java.util.List<String> segments = new java.util.ArrayList<>();
                for (String seg : normalized.split("\r")) {
                    if (!seg.isBlank()) segments.add(seg);
                }
                yield HL7ResultParser.parse(segments, profileRecognition);
            }
            case ASTM -> {
                String raw = envelope.getRawMessage();
                if (raw == null || raw.isBlank()) yield null;
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (String l : raw.split("[\\r\\n]+")) {
                    if (!l.isBlank()) lines.add(l);
                }
                yield ASTMResultParser.parse(lines, profileRecognition,
                        registeredAnalyzer.get().getAstmResultRecordSelection());
            }
            case CSV -> {
                // CSV over HTTP/TCP uses same ASTM record format
                String raw = envelope.getRawMessage();
                if (raw == null || raw.isBlank()) yield null;
                java.util.List<String> lines = new java.util.ArrayList<>();
                for (String l : raw.split("[\\r\\n]+")) {
                    if (!l.isBlank()) lines.add(l);
                }
                yield ASTMResultParser.parse(lines, profileRecognition,
                        registeredAnalyzer.get().getAstmResultRecordSelection());
            }
            default -> {
                log.error("FHIR routing: unsupported protocol {} from {} — cannot parse",
                        envelope.getProtocol(), envelope.getSourceId());
                yield null;
            }
        };

        if (parsed == null || parsed.results().isEmpty()) {
            String raw = envelope.getRawMessage();
            String preview = raw != null ? raw.substring(0, Math.min(300, raw.length()))
                    .replace("\r", "\\r").replace("\n", "\\n") : "null";
            log.error("FHIR parse produced no results for {} message from {}. "
                    + "Raw length: {} chars. Preview: [{}]",
                    envelope.getProtocol(), envelope.getSourceId(),
                    raw != null ? raw.length() : 0, preview);
            return false;
        }

        // Build a FHIR Bundle with the registered analyzer identity.
        String analyzerId = canonicalAnalyzerId(envelope);
        FhirBundleBuilder.DeviceInfo deviceInfo = FhirBundleBuilder.DeviceInfo
                .fromSenderToken(envelope.getSourceId(), envelope.getProtocolAnalyzerHint());
        // Resolve analyzer code to LOINC from the same registered profile pin.
        java.util.function.Function<String, String> codeToLoinc =
                registeredAnalyzer.get()::getLoincForCode;
        String fhirJson = FhirBundleBuilder.buildBundle(
                parsed.accessionNumber(),
                analyzerId,
                parsed.results(),
                deviceInfo,
                codeToLoinc);

        // Build target URI for /analyzer/fhir
        URI targetUri = buildFhirTargetUri();

        log.info("FHIR routing {} results for accession {} from {} to {}",
                parsed.results().size(), parsed.accessionNumber(),
                envelope.getSourceId(), targetUri);

        // Send with retry
        int maxAttempts = httpConfig.getMaxAttempts();
        long backoffMs = httpConfig.getBackoffMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .header("Content-Type", "application/fhir+json")
                        .header(HEADER_SOURCE_PROTOCOL, envelope.getProtocol().name())
                        .header(HEADER_SOURCE_TRANSPORT, envelope.getTransport().name())
                        .header(HEADER_SOURCE_ID, envelope.getSourceId())
                        .timeout(Duration.ofSeconds(readTimeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(fhirJson));

                if (analyzerId != null && !analyzerId.isEmpty()) {
                    builder.header(HEADER_ANALYZER_ID, analyzerId);
                }
                if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
                    addBasicAuth(builder, httpConfig.getUsername(), httpConfig.getPassword());
                }

                HttpResponse<String> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("FHIR Bundle accepted by OE ({} results)", parsed.results().size());
                    return true;
                }
                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    log.error("OE rejected FHIR Bundle (HTTP {}): {}",
                            response.statusCode(), response.body());
                    recordRejection(envelope, fhirJson, response.statusCode(),
                        "OE rejected FHIR Bundle: " + truncate(response.body(), 400));
                    return false;
                }
                log.warn("OE returned {} for FHIR Bundle, attempt {}/{}",
                        response.statusCode(), attempt, maxAttempts);
            } catch (IOException e) {
                log.warn("IO error sending FHIR Bundle, attempt {}/{}: {}",
                        attempt, maxAttempts, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            if (attempt < maxAttempts) {
                long waitMs = Math.min(backoffMs * (1L << (attempt - 1)), MAX_BACKOFF_MS);
                try { Thread.sleep(waitMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.error("All {} FHIR forwarding attempts failed for {} message from {}",
            maxAttempts, envelope.getProtocol(), envelope.getSourceId());
        recordRejection(envelope, fhirJson, 0,
            "All " + maxAttempts + " FHIR attempts failed (5xx / IO)");
        return false;
    }

    /**
     * Persist a rejected payload to the shared state store so the admin
     * endpoint and OE Import Issues dashboard can surface it. No-op when
     * {@code stateStore} is null (unit tests without Spring context, or
     * {@code bridge.file.enabled=false}) — the ERROR log above still fires.
     */
    private void recordRejection(MessageEnvelope envelope, String payload, int httpStatus,
                                 String lastError) {
        if (stateStore == null) {
            return;
        }
        try {
            String id = stateStore.recordRejection(
                envelope.getSourceId(),
                envelope.getProtocol() == null ? null : envelope.getProtocol().name(),
                httpStatus,
                lastError,
                payload);
            log.warn("Recorded rejected bundle id={} source={} httpStatus={}",
                id, envelope.getSourceId(), httpStatus);
        } catch (RuntimeException e) {
            // Never let the diagnostic store block the hot path; worst case
            // the rejection is visible in logs only.
            log.error("Failed to persist rejected bundle (source={}, httpStatus={}): {}",
                envelope.getSourceId(), httpStatus, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private URI buildFhirTargetUri() {
        URI baseUri = httpConfig.getUri();
        String basePath = baseUri.getPath();
        if (basePath == null || basePath.isEmpty()) basePath = "/analyzer";
        else if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        try {
            return new URI(baseUri.getScheme(), baseUri.getUserInfo(), baseUri.getHost(),
                    baseUri.getPort(), basePath + "/fhir", baseUri.getQuery(), baseUri.getFragment());
        } catch (URISyntaxException e) {
            log.error("Failed to build FHIR target URI", e);
            return baseUri;
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
        String analyzerId = canonicalAnalyzerId(envelope);
        if (analyzerId != null && !analyzerId.isEmpty()) {
            builder.header(HEADER_ANALYZER_ID, analyzerId);
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

    private String canonicalAnalyzerId(MessageEnvelope envelope) {
        if (envelope.getResolvedAnalyzerId() != null && !envelope.getResolvedAnalyzerId().isBlank()) {
            return envelope.getResolvedAnalyzerId();
        }
        return envelope.getAnalyzerId();
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
