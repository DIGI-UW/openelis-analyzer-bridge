package org.itech.ahb.normalizer;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.MessageRouter;
import org.itech.ahb.util.DeadLetterWriter;
import org.itech.ahb.util.OeApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Central message orchestration service for the Universal Analyzer Bridge.
 * <p>
 * MessageNormalizer implements the {@link MessageRouter} interface and serves as
 * the @Primary bean, ensuring that all message routing in the application flows
 * through this component. This design allows existing MLLP code (which depends on
 * MessageRouter) to automatically use the normalizer without any code changes.
 * </p>
 * <p>
 * The normalizer orchestrates:
 * <ul>
 *   <li>Analyzer identification via {@link AnalyzerIdentifier} (source binding)</li>
 *   <li>Message enrichment with canonical analyzer ID and protocol hint metadata</li>
 *   <li>Routing to OpenELIS via {@link HttpForwardingRouter}</li>
 *   <li>Audit logging of all message flows</li>
 * </ul>
 * </p>
 * <p>
 * All transport listeners (MLLP, Serial, File, HTTP Input, ASTM TCP) delegate to
 * this service after creating a {@link MessageEnvelope} with transport metadata.
 * </p>
 *
 * @see MessageRouter
 * @see HttpForwardingRouter
 * @see AnalyzerIdentifier
 * @see MessageEnvelope
 */
@Component
@Primary  // CRITICAL: Makes this the default MessageRouter bean
@Slf4j
public class MessageNormalizer implements MessageRouter {

    private final HttpForwardingRouter forwardingRouter;  // Inject by CONCRETE TYPE
    private final AnalyzerIdentifier identifier;
    private final AnalyzerRegistryConfig registry;  // optional — for diagnostic validation only
    private final MetricsService metricsService;  // nullable — optional dependency
    private final OeApiClient oeApiClient;  // nullable — for discovered-source reporting
    private final DeadLetterWriter deadLetterWriter;  // nullable — for DLQ on unknown sources

    /**
     * Constructs a new MessageNormalizer.
     * <p>
     * Injects {@link HttpForwardingRouter} by concrete type (not MessageRouter interface)
     * to avoid circular injection ambiguity, since this class also implements MessageRouter.
     * </p>
     *
     * @param forwardingRouter the HTTP forwarding router for sending to OpenELIS
     * @param identifier the analyzer identification service
     * @param metricsService optional metrics service (null if MetricsService bean is not created)
     */
    public MessageNormalizer(
            HttpForwardingRouter forwardingRouter,
            AnalyzerIdentifier identifier,
            MetricsService metricsService) {
        this(forwardingRouter, identifier, null, metricsService, null, null);
    }

    @Autowired
    public MessageNormalizer(
            HttpForwardingRouter forwardingRouter,
            AnalyzerIdentifier identifier,
            @Autowired(required = false) AnalyzerRegistryConfig registry,
            @Autowired(required = false) MetricsService metricsService,
            @Autowired(required = false) OeApiClient oeApiClient,
            @Autowired(required = false) DeadLetterWriter deadLetterWriter) {
        this.forwardingRouter = forwardingRouter;
        this.identifier = identifier;
        this.registry = registry;
        this.metricsService = metricsService;
        this.oeApiClient = oeApiClient;
        this.deadLetterWriter = deadLetterWriter;
    }

    /**
     * MessageRouter.route() implementation — allows MLLP to use this transparently.
     * <p>
     * This method enables existing code that depends on {@link MessageRouter} to
     * automatically use the normalizer via Spring's @Primary bean selection.
     * </p>
     * <p>
     * Delegates to {@link #process(MessageEnvelope)} for the actual implementation.
     * </p>
     *
     * @param envelope the message with transport metadata
     * @return true if routing succeeded, false otherwise
     */
    @Override
    public boolean route(MessageEnvelope envelope) {
        return process(envelope);
    }

    /**
     * Process a message envelope: resolve analyzer identity, enrich envelope, route to OpenELIS.
     * <p>
     * Called directly by Serial/File/HTTP/ASTM handlers, or via {@link #route(MessageEnvelope)}
     * by MLLP. This method:
     * <ol>
     *   <li>Resolves canonical analyzer ID from source binding</li>
     *   <li>Validates protocol hint consistency (if provided)</li>
     *   <li>Enriches the envelope with canonical resolved analyzer metadata</li>
     *   <li>Logs the routing operation for audit purposes</li>
     *   <li>Routes to OpenELIS via {@link HttpForwardingRouter}</li>
     * </ol>
     * </p>
     *
     * @param envelope the message with transport metadata
     * @return true if routing succeeded, false otherwise
     */
    public boolean process(MessageEnvelope envelope) {
        if (envelope == null) {
            log.error("Cannot process null MessageEnvelope");
            return false;
        }
        if (envelope.getProtocol() == null || envelope.getTransport() == null) {
            log.error("MessageEnvelope missing protocol or transport: protocol={}, transport={}, sourceId={}, analyzerId={}",
                envelope.getProtocol(), envelope.getTransport(),
                envelope.getSourceId(), envelope.getAnalyzerId());
            return false;
        }
        if (envelope.getSourceId() == null || envelope.getSourceId().trim().isEmpty()) {
            log.error("MessageEnvelope missing sourceId for {}", envelope.getProtocol());
            return false;
        }
        if (envelope.getRawMessage() == null || envelope.getRawMessage().trim().isEmpty()) {
            log.error("MessageEnvelope missing rawMessage for {}", envelope.getProtocol());
            return false;
        }

        String protocol = envelope.getProtocol().name();
        String transport = envelope.getTransport().name();

        // Start timing
        Timer.Sample sample = metricsService != null ? metricsService.startRouting() : null;

        // Record message received
        if (metricsService != null) {
            metricsService.recordReceived(protocol, transport);
        }

        String protocolHint = firstNonBlank(envelope.getProtocolAnalyzerHint(), envelope.getAnalyzerId());
        String resolvedAnalyzerId = identifier.identify(envelope);

        // Policy: source-binding registration is authoritative for routing.
        if (resolvedAnalyzerId == null || resolvedAnalyzerId.isBlank()) {
            log.warn("No registered analyzer for source '{}'; protocolHint='{}' — message will not be routed",
                envelope.getSourceId(), protocolHint);
            handleUnknownSource(envelope, protocolHint);
            if (metricsService != null) {
                metricsService.recordRouted(sample, protocol, transport, false);
            }
            return false;
        }

        AnalyzerRegistryConfig.AnalyzerEntry registryEntry = registry != null
            ? registry.findAnalyzerEntry(envelope.getSourceId()).orElse(null)
            : null;

        // Policy: protocol hints are diagnostic evidence only. Routing remains bound to the
        // source-registered OpenELIS analyzer ID, even when the protocol-level sender token
        // uses a different namespace (for example, GENEXPERT vs OE analyzer id 44).
        if (protocolHint != null && !protocolHint.equals(resolvedAnalyzerId)) {
            if (registryEntry != null && protocolHintMatchesRegistration(protocolHint, registryEntry)) {
                log.info("Analyzer identity evidence matched registered analyzer for source '{}': resolved='{}', protocolHint='{}', registeredName='{}'",
                    envelope.getSourceId(), resolvedAnalyzerId, protocolHint, registryEntry.getName());
            } else {
                log.warn("Analyzer identity evidence mismatch for source '{}': resolved='{}', protocolHint='{}', registeredName='{}' — routing will continue with resolved analyzer",
                    envelope.getSourceId(), resolvedAnalyzerId, protocolHint,
                    registryEntry != null ? registryEntry.getName() : "unknown");
            }
        }

        // Rebuild envelope with explicit canonical ID and protocol hint.
        MessageEnvelope enriched = MessageEnvelope.builder()
            .protocol(envelope.getProtocol())
            .transport(envelope.getTransport())
            .sourceId(envelope.getSourceId())
            .sourcePort(envelope.getSourcePort())
            .rawMessage(envelope.getRawMessage())
            .receivedAt(envelope.getReceivedAt())
            .protocolAnalyzerHint(protocolHint)
            .resolvedAnalyzerId(resolvedAnalyzerId)
            .analyzerId(resolvedAnalyzerId)
            .build();

        // 3. Audit log
        log.info("Normalizer processing: protocol={}, transport={}, source={}, resolvedAnalyzer={}, protocolHint={}",
            enriched.getProtocol(), enriched.getTransport(),
            enriched.getSourceId(), enriched.getResolvedAnalyzerId(), enriched.getProtocolAnalyzerHint());

        // 4. Route via HttpForwardingRouter (NOT via this.route() — that would recurse!)
        boolean success = forwardingRouter.route(enriched);

        // Record routing result and duration
        if (metricsService != null) {
            metricsService.recordRouted(sample, protocol, transport, success);
        }

        if (!success) {
            log.error("Failed to route message: protocol={}, source={}",
                enriched.getProtocol(), enriched.getSourceId());
        }

        return success;
    }

    private void handleUnknownSource(MessageEnvelope envelope, String protocolHint) {
        // Report discovered source to OE (creates PENDING_REGISTRATION stub)
        if (oeApiClient != null) {
            try {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("sourceId", envelope.getSourceId());
                body.put("protocol", envelope.getProtocol() != null ? envelope.getProtocol().name() : null);
                body.put("protocolHint", protocolHint);
                body.put("transport", envelope.getTransport() != null ? envelope.getTransport().name() : null);
                Map<String, Object> result = oeApiClient.post("/rest/analyzer/discovered-sources", body);
                if (result != null) {
                    log.info("Reported unknown source '{}' to OE: analyzerId={}, alreadyExists={}",
                        envelope.getSourceId(), result.get("analyzerId"), result.get("alreadyExists"));
                }
            } catch (Exception e) {
                log.warn("Failed to report unknown source '{}' to OE: {}",
                    envelope.getSourceId(), e.getMessage());
            }
        }

        // Write message to dead-letter directory
        if (deadLetterWriter != null) {
            deadLetterWriter.write(envelope, "UNREGISTERED_SOURCE");
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private boolean protocolHintMatchesRegistration(
            String protocolHint,
            AnalyzerRegistryConfig.AnalyzerEntry registryEntry) {
        if (protocolHint == null || protocolHint.isBlank() || registryEntry == null) {
            return false;
        }

        String normalizedHint = normalizeIdentityToken(protocolHint);
        String normalizedName = normalizeIdentityToken(registryEntry.getName());
        String normalizedId = normalizeIdentityToken(registryEntry.getId());

        if (normalizedHint.isEmpty()) {
            return false;
        }

        return (!normalizedName.isEmpty() && normalizedName.contains(normalizedHint))
            || (!normalizedId.isEmpty() && normalizedId.equals(normalizedHint));
    }

    private String normalizeIdentityToken(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }
}
