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
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
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
 * Parses registered analyzer traffic using the pinned Bridge profile and sends
 * the versioned normalized result contract to OpenELIS at
 * {@code /analyzer/fhir}. The durable Bridge connection identifier in the
 * bundle is the routing identity consumed by OpenELIS.
 * </p>
 *
 * @see MessageRouter
 * @see org.itech.ahb.normalizer.MessageEnvelope
 */
@Component
@Slf4j
public class HttpForwardingRouter implements MessageRouter {

    /** Maximum backoff wait time in milliseconds (1 minute cap to prevent overflow) */
    private static final long MAX_BACKOFF_MS = 60_000;

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final SqliteFileStateStore stateStore;
    private final AnalyzerRuntimeRegistry registry;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final HttpClient httpClient;

    /**
     * Constructs a new HttpForwardingRouter with the specified configuration.
     *
     * @param httpConfig the HTTP forwarding server configuration
     * @param stateStore shared SQLite state store (optional — when absent the
     *                   router logs rejections without persisting them, same
     *                   as the pre-B1 behavior)
     * @param registry   the analyzer registry for pinned profile recognition
     */
    public HttpForwardingRouter(
            HTTPForwardServerConfigurationProperties httpConfig,
            @Autowired(required = false) SqliteFileStateStore stateStore,
            @Autowired(required = false) AnalyzerRuntimeRegistry registry) {
        this.httpConfig = httpConfig;
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

        return routeNormalized(envelope);
    }

    /**
     * Route a message as a FHIR R4 transaction Bundle.
     *
     * <p>Parses the raw message using the protocol-specific parser, builds a FHIR
     * Bundle, and POSTs to OE's {@code /analyzer/fhir} endpoint.
     */
    private boolean routeNormalized(MessageEnvelope envelope) {
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
        FhirBundleBuilder.DeviceInfo deviceInfo = FhirBundleBuilder.DeviceInfo
                .fromSenderToken(envelope.getSourceId(), envelope.getProtocolAnalyzerHint());
        AnalyzerRuntimeRegistry.AnalyzerEntry analyzer = registeredAnalyzer.orElseThrow();
        FhirBundleBuilder.AnalyzerContext analyzerContext = new FhirBundleBuilder.AnalyzerContext(
                analyzer.getBridgeConnectionId(),
                analyzer.getId(),
                analyzer.getProfileId(),
                analyzer.getProfileRevision(),
                envelope.getProtocol().name(),
                envelope.getTransport().name(),
                deviceInfo,
                analyzer.getControlResultRecognition(),
                analyzer.getRecognitionFingerprint());
        // Resolve analyzer code to LOINC from the same registered profile pin.
        java.util.function.Function<String, String> codeToLoinc =
                analyzer::getLoincForCode;
        String fhirJson = FhirBundleBuilder.buildNormalizedBundle(
                parsed.accessionNumber(),
                parsed.results(),
                analyzerContext,
                codeToLoinc);

        // Build target URI for /analyzer/fhir
        URI targetUri = buildNormalizedTargetUri();

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
                        .timeout(Duration.ofSeconds(readTimeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(fhirJson));

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

    private URI buildNormalizedTargetUri() {
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
