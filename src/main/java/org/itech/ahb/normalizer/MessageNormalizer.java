package org.itech.ahb.normalizer;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import java.util.regex.Pattern;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerInboundTransport;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.Receipt;
import org.itech.ahb.outbox.ReceivedMessage;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.MessageRouter;
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
    private final AnalyzerRuntimeRegistry registry;
    private final MetricsService metricsService;  // nullable — optional dependency

    /**
     * Durable store for every received message. Deliberately required rather than optional: the
     * store this replaced was optional and gated on the FILE transport being enabled, so switching
     * off the file watcher silently switched off failure tracking for every transport. A store whose
     * whole purpose is that nothing received is lost cannot be something a deployment turns off by
     * accident.
     */
    private final OutboxStore outbox;

    @Autowired
    public MessageNormalizer(
            HttpForwardingRouter forwardingRouter,
            AnalyzerIdentifier identifier,
            OutboxStore outbox,
            @Autowired(required = false) AnalyzerRuntimeRegistry registry,
            @Autowired(required = false) MetricsService metricsService) {
        this.forwardingRouter = forwardingRouter;
        this.identifier = identifier;
        this.outbox = outbox;
        this.registry = registry;
        this.metricsService = metricsService;
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
                envelope.getSourceId(), envelope.getResolvedAnalyzerId());
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

        // ASTM queries carry no results. Persisting them would fill the dead-message queue with
        // traffic that was never deliverable, so they are recognized before anything is stored and
        // handled where they always were, below.
        boolean queryOnly = envelope.getProtocol() == Protocol.ASTM
            && isQueryOnlyAstmMessage(envelope.getRawMessage());

        // Persist before anything else can fail. From here the message is recoverable even if
        // identity resolution, parsing or OpenELIS itself is what goes wrong.
        Receipt receipt = null;
        if (!queryOnly) {
            try {
                receipt = outbox.receive(new ReceivedMessage(
                    envelope.getSourceId(),
                    envelope.getSourcePort(),
                    envelope.getProtocol(),
                    envelope.getTransport(),
                    envelope.getProtocolAnalyzerHint(),
                    envelope.getRawMessage(),
                    null,
                    envelope.getReceivedAt(),
                    envelope.getListenerPort()));
            } catch (RuntimeException e) {
                // Nothing was stored, so the transport must refuse the message rather than imply the
                // bridge has it. For analyzers that resend on a negative acknowledgement this is the
                // one response that can still save the result.
                log.error("Could not persist a received {} message from {}; refusing it so it is not lost silently",
                    protocol, envelope.getSourceId(), e);
                if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
                return false;
            }
            if (receipt.alreadyPresent()) {
                log.info("Message from {} is identical to one already held in the outbox ({}); not storing it twice",
                    envelope.getSourceId(), receipt.id());
            }
        }

        // Record message received
        if (metricsService != null) {
            metricsService.recordReceived(protocol, transport);
        }

        String protocolHint = envelope.getProtocolAnalyzerHint();

        AnalyzerRuntimeRegistry.Resolution resolution = registry != null
          ? registry.resolve(envelope.getListenerPort(), envelope.getSourceId(), protocolHint)
          : null;
        AnalyzerRuntimeRegistry.AnalyzerEntry registryEntry =
          resolution instanceof AnalyzerRuntimeRegistry.Resolution.Resolved resolved ? resolved.entry() : null;
        if (resolution instanceof AnalyzerRuntimeRegistry.Resolution.Ambiguous ambiguous) {
          recordIdentity(protocol, transport, "ambiguous_source");
          log.warn("Holding message from '{}' in the dead-message queue: {}", envelope.getSourceId(), ambiguous.detail());
          if (receipt != null) {
            outbox.markDeadLettered(receipt.id(), FailureReason.AMBIGUOUS_SOURCE, ambiguous.detail());
          }
          if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
          return false;
        }
        if (registry != null && !AnalyzerInboundTransport.matches(envelope.getProtocol(), envelope.getTransport(), registryEntry)) {
          recordIdentity(protocol, transport, registryEntry == null ? "unregistered_source" : "transport_mismatch");
          log.warn(
            "Rejecting protocol/transport inconsistent with saved connection for source '{}'",
            envelope.getSourceId()
          );
          if (receipt != null) {
            outbox.markDeadLettered(
              receipt.id(),
              registryEntry == null ? FailureReason.UNREGISTERED_SOURCE : FailureReason.CONNECTION_TRANSPORT_MISMATCH,
              registryEntry == null
                ? ((AnalyzerRuntimeRegistry.Resolution.Unregistered) resolution).detail()
                : "Source " + envelope.getSourceId() + " sent over a transport its saved connection does not accept");
          }
          if (metricsService != null) metricsService.recordRouted(sample, protocol, transport, false);
          return false;
        }

        // Skip ASTM Q-only messages (queries with no R-records) — they don't carry
        // results to forward. Until bidirectional order-response is implemented
        // (OGC-335/336), log INFO and ack as success. Without this, the result
        // parser logs ERROR for every Q-record and clutters operational telemetry.
        if (queryOnly) {
            log.info("ASTM query message from source '{}' (no R-records) — protocolHint='{}'. "
                + "Skipping result-parser path; bidirectional order-response not implemented.",
                envelope.getSourceId(), protocolHint);
            if (metricsService != null) {
                metricsService.recordRouted(sample, protocol, transport, true);
            }
            return true;
        }

        String resolvedAnalyzerId = identifier.identify(envelope);

        if (resolvedAnalyzerId == null || resolvedAnalyzerId.isBlank()) {
            recordIdentity(protocol, transport, "unregistered_source");
            log.warn("Rejecting unregistered analyzer source '{}'; protocolHint='{}' is evidence only",
                envelope.getSourceId(), protocolHint);
            if (receipt != null) {
                outbox.markDeadLettered(
                    receipt.id(),
                    FailureReason.UNREGISTERED_SOURCE,
                    "No registered analyzer resolves source " + envelope.getSourceId());
            }
            if (metricsService != null) {
                metricsService.recordRouted(sample, protocol, transport, false);
            }
            return false;
        }

        // The source-bound saved connection is routing authority. A protocol hint
        // can corroborate or contradict it, but never replace it.
        boolean haveHint = protocolHint != null && !protocolHint.isBlank();
        if (haveHint) {
            boolean agrees = registryEntry != null
                && protocolHintMatchesRegistration(protocolHint, registryEntry);
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
            .listenerPort(envelope.getListenerPort())
            .resolvedAnalyzerId(resolvedAnalyzerId)
            .outboxReceiptId(receipt == null ? null : receipt.id())
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

    private boolean protocolHintMatchesRegistration(
            String protocolHint,
            AnalyzerRuntimeRegistry.AnalyzerEntry registryEntry) {
        if (protocolHint == null || protocolHint.isBlank() || registryEntry == null) {
            return false;
        }

        // The pinned profile's identifier pattern is the only content signal that
        // can corroborate the source-bound connection.
        Pattern idPattern = registryEntry.getCompiledIdentifierPattern();
        return idPattern != null && idPattern.matcher(protocolHint).find();
    }
}
