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
     * Optional analyzer identifier (if pre-configured or detected from message headers)
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
