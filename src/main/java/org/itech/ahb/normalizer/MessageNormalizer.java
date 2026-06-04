package org.itech.ahb.normalizer;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.model.Protocol;
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
    private final java.util.concurrent.Executor asyncExecutor;

    /**
     * Minimal constructor for tests and non-discovery use cases.
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
        this(forwardingRouter, identifier, registry, metricsService, oeApiClient, deadLetterWriter,
                java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Test constructor — accepts a custom executor for deterministic async verification. */
    public MessageNormalizer(
            HttpForwardingRouter forwardingRouter,
            AnalyzerIdentifier identifier,
            AnalyzerRegistryConfig registry,
            MetricsService metricsService,
            OeApiClient oeApiClient,
            DeadLetterWriter deadLetterWriter,
            java.util.concurrent.Executor asyncExecutor) {
        this.forwardingRouter = forwardingRouter;
        this.identifier = identifier;
        this.registry = registry;
        this.metricsService = metricsService;
        this.oeApiClient = oeApiClient;
        this.deadLetterWriter = deadLetterWriter;
        this.asyncExecutor = asyncExecutor;
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

        // Skip ASTM Q-only messages (queries with no R-records) — they don't carry
        // results to forward. Until bidirectional order-response is implemented
        // (OGC-335/336), log INFO and ack as success. Without this, the result
        // parser logs ERROR for every Q-record and clutters operational telemetry.
        if (envelope.getProtocol() == Protocol.ASTM && isQueryOnlyAstmMessage(envelope.getRawMessage())) {
            log.info("ASTM query message from source '{}' (no R-records) — protocolHint='{}'. "
                + "Skipping result-parser path; bidirectional order-response not implemented.",
                envelope.getSourceId(), protocolHint);
            if (metricsService != null) {
                metricsService.recordRouted(sample, protocol, transport, true);
            }
            return true;
        }

        String resolvedAnalyzerId = identifier.identify(envelope);

        // Transparent-pipe principle: routing is NEVER gated on local source
        // registration. If we don't recognize the source, we forward the
        // bundle anyway with full identification (sourceId + protocolHint
        // travel as Device resource fields) and let OE handle find-or-create
        // stub atomically per FHIR-bundle import. See
        // .claude/projects/.../memory/feedback_bridge_transparent_fhir_pipe.md
        // for the architectural rule.
        if (resolvedAnalyzerId == null || resolvedAnalyzerId.isBlank()) {
            log.info("Source '{}' not in local registry — forwarding bundle anyway. "
                + "OE will find-or-create stub from Device resource. protocolHint='{}'",
                envelope.getSourceId(), protocolHint);
        }

        AnalyzerRegistryConfig.AnalyzerEntry registryEntry = registry != null
            ? registry.findAnalyzerEntry(envelope.getSourceId()).orElse(null)
            : null;

        // Corroborate the two analyzer-identity signals: the connection source IP
        // (resolvedAnalyzerId) and the in-message sender (protocolHint). Identity
        // NEVER gates routing — the transparent-pipe rule above stands — this only
        // surfaces a warning + metric so a silent content-only fallback can't hide
        // delivery bugs the way it hid the analyzer-demo flake. Routing remains
        // bound to the IP-resolved analyzer when present; otherwise OE resolves
        // from the FHIR Device resource carrying the same hint.
        boolean haveIp = resolvedAnalyzerId != null && !resolvedAnalyzerId.isBlank();
        boolean haveHint = protocolHint != null && !protocolHint.isBlank();
        if (haveIp && haveHint) {
            boolean agrees = protocolHint.equals(resolvedAnalyzerId)
                || (registryEntry != null && protocolHintMatchesRegistration(protocolHint, registryEntry));
            if (agrees) {
                recordIdentity(protocol, transport, "corroborated");
                log.info("Analyzer identity corroborated for source '{}': resolved='{}', protocolHint='{}', registeredName='{}'",
                    envelope.getSourceId(), resolvedAnalyzerId, protocolHint,
                    registryEntry != null ? registryEntry.getName() : "unknown");
            } else {
                recordIdentity(protocol, transport, "mismatch");
                log.warn("Analyzer identity mismatch for source '{}': resolved='{}', protocolHint='{}', registeredName='{}' — routing continues with the IP-resolved analyzer",
                    envelope.getSourceId(), resolvedAnalyzerId, protocolHint,
                    registryEntry != null ? registryEntry.getName() : "unknown");
            }
        } else if (!haveIp && haveHint) {
            recordIdentity(protocol, transport, "degraded_content_only");
            log.warn("Analyzer identity degraded for source '{}': connection source IP did not resolve to a registered analyzer; routing on the in-message sender alone (protocolHint='{}')",
                envelope.getSourceId(), protocolHint);
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

    /**
     * Detect ASTM Q-only messages (queries with no R-records).
     *
     * <p>Bidirectional ASTM analyzers (GeneXpert, etc.) periodically send
     * Q-records asking the LIS for pending orders. These messages carry no
     * results — they're requests, not responses. Trying to parse them as
     * results produces "FHIR parse produced no results" errors that
     * cluttered telemetry until this filter was added (see plan
     * .claude/plans/abundant-chasing-hoare.md Issue #3).
     *
     * <p>Until OGC-335/336 implements the bidirectional order-response
     * handler, the right thing to do is detect Q-only messages early,
     * log INFO, and ack as success (we received and intentionally
     * declined to forward).
     *
     * @param rawMessage the ASTM message payload
     * @return true if the message contains a Q-record but no R-record
     */
    private boolean isQueryOnlyAstmMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return false;
        boolean hasQ = false;
        boolean hasR = false;
        for (String segment : rawMessage.split("\r")) {
            String trimmed = segment.trim();
            if (trimmed.startsWith("Q|")) hasQ = true;
            if (trimmed.startsWith("R|")) hasR = true;
        }
        return hasQ && !hasR;
    }

    private void recordIdentity(String protocol, String transport, String mode) {
        if (metricsService != null) {
            metricsService.recordIdentityMismatch(protocol, transport, mode);
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

        // Primary: corroborate against the SAME regex OE uses to identify the
        // sender (Analyzer.identifierPattern). This is authoritative and keeps the
        // two identity signals consistent — it matches ASTM caret-delimited
        // senders ("GENEXPERT^GeneXpert^4.6.0") and HL7 MSH-3/4 hints alike,
        // without the false-positive risk of a manufacturer-substring heuristic.
        // The pattern is compiled once at registration/sync time and cached on the
        // entry (CASE_INSENSITIVE); invalid patterns are rejected/ignored there and
        // never reach here, so this is a pure matcher().find() per message.
        Pattern idPattern = registryEntry.getCompiledIdentifierPattern();
        if (idPattern != null && idPattern.matcher(protocolHint).find()) {
            return true;
        }

        // Fallback for analyzers registered without a pattern: normalized name/id.
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
