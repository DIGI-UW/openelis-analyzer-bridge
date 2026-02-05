package org.itech.ahb.model;

/**
 * Represents the message protocol format used by analyzers.
 * <p>
 * Protocol defines the structure and syntax of the message content,
 * independent of how the message is transported.
 * </p>
 */
public enum Protocol {
    /**
     * ASTM LIS2-A2 protocol - used by many clinical analyzers
     */
    ASTM,

    /**
     * HL7 v2.x protocol - health level 7 messaging standard
     */
    HL7,

    /**
     * CSV (Comma-Separated Values) - simple tabular format
     */
    CSV,

    /**
     * Unknown/unrecognized protocol
     */
    UNKNOWN
}
