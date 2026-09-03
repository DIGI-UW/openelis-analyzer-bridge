package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
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
 * @see AnalyzerRuntimeRegistry
 * @see MessageEnvelope
 */
@Component
@Slf4j
public class AnalyzerIdentifier {

    private final AnalyzerRuntimeRegistry registry;

    /**
     * Constructs a new AnalyzerIdentifier.
     * <p>
     * @param registry the active connection projection
     */
    public AnalyzerIdentifier(AnalyzerRuntimeRegistry registry) {
        this.registry = registry;
        log.info("AnalyzerIdentifier created with {} registered analyzers",
                registry.getRegisteredAnalyzers().size());
    }

    /**
     * Identifies the analyzer for a message envelope using source-binding lookup.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Look up the source in the active connection projection</li>
     *   <li>Return null when no active connection owns the source</li>
     * </ol>
     * </p>
     *
     * @param envelope the message envelope with source metadata
     * @return the identified analyzer ID, or null if identification failed/not configured
     */
    public String identify(MessageEnvelope envelope) {
        // Registry-based lookup by source ID -> canonical OE analyzer ID.
        // Protocol-level hints are handled by MessageNormalizer as diagnostics only.
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
