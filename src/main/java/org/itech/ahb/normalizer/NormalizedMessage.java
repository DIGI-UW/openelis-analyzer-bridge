package org.itech.ahb.normalizer;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

import java.time.Instant;

/**
 * Standardized message format sent to OpenELIS via HTTP POST.
 * <p>
 * NormalizedMessage provides a consistent JSON structure regardless of the
 * original transport or protocol used by the analyzer. OpenELIS always receives
 * this same format, whether the analyzer sent via TCP, MLLP, Serial, File, or HTTP.
 * </p>
 */
@Getter
@Builder
@ToString
public class NormalizedMessage {
    /**
     * Analyzer identifier (from configuration or message headers)
     * <p>
     * Used by OpenELIS to lookup the analyzer configuration and apply
     * the correct result mappings.
     * </p>
     */
    private final String analyzerId;

    /**
     * Message protocol format (ASTM, HL7, CSV)
     */
    private final Protocol protocol;

    /**
     * Transport method used to receive the message (TCP, MLLP, SERIAL, FILE, HTTP)
     */
    private final Transport transport;

    /**
     * Raw message content (preserves original format for OpenELIS parsing)
     */
    private final String message;

    /**
     * Source identifier (IP address, serial port, file path)
     * <p>
     * Provides additional context for debugging and audit logging.
     * </p>
     */
    private final String sourceId;

    /**
     * Timestamp when the bridge received the message
     */
    private final Instant timestamp;

    /**
     * Optional message identifier (from ASTM header or HL7 MSH segment)
     */
    private final String messageId;

    /**
     * Optional error message if protocol detection or parsing failed
     */
    private final String error;
}
