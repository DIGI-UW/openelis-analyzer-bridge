package org.itech.ahb.model;

/**
 * Represents the transport/delivery method used to receive messages from analyzers.
 * <p>
 * Transport defines HOW the message is transmitted, independent of the message
 * protocol format (ASTM, HL7, CSV, etc.).
 * </p>
 */
public enum Transport {
    /**
     * Raw TCP socket connection (e.g., ASTM over TCP)
     */
    TCP,

    /**
     * MLLP (Minimal Lower Layer Protocol) - HL7's standard TCP framing
     * Uses VT (0x0B) start, FS (0x1C) + CR (0x0D) end markers
     */
    MLLP,

    /**
     * Serial port communication (RS-232, RS-485, etc.)
     */
    SERIAL,

    /**
     * File-based communication (watch directory for new files)
     */
    FILE,

    /**
     * HTTP POST endpoint
     */
    HTTP
}
