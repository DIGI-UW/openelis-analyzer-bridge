package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Multi-strategy analyzer identification service.
 * <p>
 * Identifies analyzers using a priority-ordered strategy chain:
 * <ol>
 *   <li><strong>Pre-identified:</strong> Envelope already has analyzerId (set by listener)</li>
 *   <li><strong>IP-based lookup:</strong> Match source IP address in registry</li>
 *   <li><strong>Serial port lookup:</strong> Match serial port path in registry</li>
 *   <li><strong>File path pattern:</strong> Match file path using glob patterns</li>
 *   <li><strong>Fallback to OpenELIS:</strong> Return null, let OpenELIS identify from message content</li>
 * </ol>
 * </p>
 * <p>
 * The analyzer registry is optional — if not configured, the service simply returns null
 * and OpenELIS will attempt to identify the analyzer from the message content (e.g., MSH-3/MSH-4
 * for HL7, ASTM record fields, CSV headers).
 * </p>
 *
 * @see AnalyzerRegistryConfig
 * @see MessageEnvelope
 */
@Component
@Slf4j
public class AnalyzerIdentifier {

    private final AnalyzerRegistryConfig registry;  // may be null if not configured

    /**
     * Constructs a new AnalyzerIdentifier.
     * <p>
     * The registry is optional (required = false) — if not configured, all identification
     * attempts will return null and OpenELIS will identify from message content.
     * </p>
     *
     * @param registry the analyzer registry configuration (may be null)
     */
    public AnalyzerIdentifier(@Autowired(required = false) AnalyzerRegistryConfig registry) {
        this.registry = registry;
        if (registry == null) {
            log.info("AnalyzerIdentifier created without registry — will delegate identification to OpenELIS");
        } else {
            int analyzerCount = registry.getAnalyzers() != null ? registry.getAnalyzers().size() : 0;
            log.info("AnalyzerIdentifier created with {} registered analyzers", analyzerCount);
        }
    }

    /**
     * Identifies the analyzer for a message envelope using multi-strategy lookup.
     * <p>
     * Strategy priority:
     * <ol>
     *   <li>If envelope already has analyzerId → use it</li>
     *   <li>If registry is configured → lookup by sourceId (IP, serial port, file pattern)</li>
     *   <li>Otherwise → return null (OpenELIS will identify from message content)</li>
     * </ol>
     * </p>
     *
     * @param envelope the message envelope with source metadata
     * @return the identified analyzer ID, or null if identification failed/not configured
     */
    public String identify(MessageEnvelope envelope) {
        // Registry-based lookup by source IP → OE analyzer ID.
        // Always runs, even if the protocol handler already set an analyzerId
        // (e.g., "GENEXPERT" from ASTM H-record), because the OE ID is the
        // authoritative identifier for routing.
        if (registry == null) {
            log.debug("No analyzer registry configured, returning null");
            return null;
        }

        String sourceId = envelope.getSourceId();
        if (sourceId == null || sourceId.isEmpty()) {
            log.debug("Envelope has no sourceId, cannot identify");
            return null;
        }

        // Lookup by sourceId (IP address, serial port path, or file path pattern)
        String analyzerId = registry.findAnalyzerId(sourceId).orElse(null);

        if (analyzerId != null) {
            log.info("Identified analyzer '{}' from source '{}' ({} transport)",
                analyzerId, sourceId, envelope.getTransport());
        } else {
            log.debug("No analyzer match for source '{}', will delegate to OpenELIS", sourceId);
        }

        return analyzerId;
    }
}
