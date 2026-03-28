package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Source-binding analyzer identification service.
 * <p>
 * Resolves the canonical OpenELIS analyzer ID from source registration:
 * <ol>
 *   <li><strong>IP-based lookup:</strong> Match source IP address in registry</li>
 *   <li><strong>Serial port lookup:</strong> Match serial port path in registry</li>
 *   <li><strong>File path pattern:</strong> Match file path using glob patterns</li>
 *   <li><strong>No match:</strong> Return null (non-routing path is handled upstream)</li>
 * </ol>
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
            log.info("AnalyzerIdentifier created without registry — source-binding resolution disabled");
        } else {
            int analyzerCount = registry.getAnalyzers() != null ? registry.getAnalyzers().size() : 0;
            log.info("AnalyzerIdentifier created with {} registered analyzers", analyzerCount);
        }
    }

    /**
     * Identifies the analyzer for a message envelope using source-binding lookup.
     * <p>
     * Strategy:
     * <ol>
     *   <li>If registry is configured → lookup by sourceId (IP, serial port, file pattern)</li>
     *   <li>Otherwise → return null</li>
     * </ol>
     * </p>
     *
     * @param envelope the message envelope with source metadata
     * @return the identified analyzer ID, or null if identification failed/not configured
     */
    public String identify(MessageEnvelope envelope) {
        // Registry-based lookup by source ID -> canonical OE analyzer ID.
        // Protocol-level hints are handled by MessageNormalizer as diagnostics only.
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
            log.debug("No analyzer match for source '{}'", sourceId);
        }

        return analyzerId;
    }
}
