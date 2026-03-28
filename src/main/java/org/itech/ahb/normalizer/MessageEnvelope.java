package org.itech.ahb.normalizer;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

import java.time.Instant;

/**
 * Internal DTO for routing messages from listeners to the normalizer.
 * <p>
 * MessageEnvelope wraps the raw message with metadata about how it was received
 * (transport type, source identifier, protocol) before it gets normalized and
 * forwarded to OpenELIS.
 * </p>
 */
@Getter
@Builder
@ToString
public class MessageEnvelope {
    /**
     * Detected or specified protocol format (ASTM, HL7, CSV, etc.)
     */
    private final Protocol protocol;

    /**
     * Transport method used to receive the message (TCP, MLLP, SERIAL, FILE, HTTP)
     */
    private final Transport transport;

    /**
     * Source identifier (IP address, serial port, file path, etc.)
     * <p>
     * Examples:
     * - TCP/MLLP/HTTP: "192.168.1.10"
     * - Serial: "/dev/ttyUSB0"
     * - File: "/mnt/analyzer-import/quantstudio/results-20260205.csv"
     * </p>
     */
    private final String sourceId;

    /**
     * Raw message content (ASTM, HL7, or CSV string)
     */
    private final String rawMessage;

    /**
     * Timestamp when the message was received by the bridge
     */
    @Builder.Default
    private final Instant receivedAt = Instant.now();

    /**
     * Optional analyzer identifier observed from protocol payload metadata.
     * <p>
     * Examples:
     * - HL7 sender app/facility
     * - ASTM H-record sender token
     * </p>
     * This value is evidence only and must not be used as the routing authority.
     */
    private final String protocolAnalyzerHint;

    /**
     * Canonical OpenELIS analyzer ID resolved by source-binding registration.
     * This is the routing authority used for downstream forwarding.
     */
    private final String resolvedAnalyzerId;

    /**
     * Backward-compatible alias for the canonical analyzer ID in downstream routing.
     * <p>
     * For new code, prefer {@link #resolvedAnalyzerId} for routing decisions and
     * {@link #protocolAnalyzerHint} for protocol-level evidence.
     * </p>
     */
    private final String analyzerId;

    /**
     * Source port number (TCP/MLLP: remote port from connection metadata; HTTP: remote port from request)
     * <p>
     * Forwarded to OpenELIS as {@code X-Source-Port} for deterministic analyzer identification
     * when combined with sourceId (IP address).
     * </p>
     */
    private final Integer sourcePort;
}
