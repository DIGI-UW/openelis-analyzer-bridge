package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.MessageRouter;
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
 *   <li>Analyzer identification via {@link AnalyzerIdentifier} (multi-strategy)</li>
 *   <li>Message enrichment (adding analyzer ID if identified)</li>
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

    /**
     * Constructs a new MessageNormalizer.
     * <p>
     * Injects {@link HttpForwardingRouter} by concrete type (not MessageRouter interface)
     * to avoid circular injection ambiguity, since this class also implements MessageRouter.
     * </p>
     *
     * @param forwardingRouter the HTTP forwarding router for sending to OpenELIS
     * @param identifier the analyzer identification service
     */
    public MessageNormalizer(
            HttpForwardingRouter forwardingRouter,
            AnalyzerIdentifier identifier) {
        this.forwardingRouter = forwardingRouter;
        this.identifier = identifier;
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
     * Process a message envelope: identify analyzer, enrich envelope, route to OpenELIS.
     * <p>
     * Called directly by Serial/File/HTTP/ASTM handlers, or via {@link #route(MessageEnvelope)}
     * by MLLP. This method:
     * <ol>
     *   <li>Attempts to identify the analyzer if not already set in envelope</li>
     *   <li>Enriches the envelope with the identified analyzer ID</li>
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

        // 1. If analyzerId not set, try to identify via AnalyzerIdentifier
        String analyzerId = envelope.getAnalyzerId();
        if (analyzerId == null || analyzerId.isEmpty()) {
            analyzerId = identifier.identify(envelope);
        }

        // 2. Rebuild envelope with analyzerId if we found one
        MessageEnvelope enriched = (analyzerId != null && !analyzerId.equals(envelope.getAnalyzerId()))
            ? MessageEnvelope.builder()
                .protocol(envelope.getProtocol())
                .transport(envelope.getTransport())
                .sourceId(envelope.getSourceId())
                .rawMessage(envelope.getRawMessage())
                .receivedAt(envelope.getReceivedAt())
                .analyzerId(analyzerId)
                .build()
            : envelope;

        // 3. Audit log
        log.info("Normalizer processing: protocol={}, transport={}, source={}, analyzer={}",
            enriched.getProtocol(), enriched.getTransport(),
            enriched.getSourceId(), enriched.getAnalyzerId());

        // 4. Route via HttpForwardingRouter (NOT via this.route() — that would recurse!)
        boolean success = forwardingRouter.route(enriched);

        if (!success) {
            log.error("Failed to route message: protocol={}, source={}",
                enriched.getProtocol(), enriched.getSourceId());
        }

        return success;
    }
}
