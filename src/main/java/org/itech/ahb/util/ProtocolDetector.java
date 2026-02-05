package org.itech.ahb.util;

import org.itech.ahb.model.Protocol;

/**
 * Utility class for auto-detecting message protocol format from message content.
 * <p>
 * ProtocolDetector examines message structure and known markers to identify
 * whether a message is ASTM, HL7, CSV, or unknown format.
 * </p>
 */
public class ProtocolDetector {

    /**
     * Detects the protocol format from message content.
     * <p>
     * Detection logic:
     * <ul>
     *   <li>ASTM: Starts with STX (0x02) or "H|\^&amp;" header</li>
     *   <li>HL7: Starts with "MSH|" segment</li>
     *   <li>CSV: Contains commas with multiple comma-separated values in first line</li>
     *   <li>UNKNOWN: None of the above patterns match</li>
     * </ul>
     * </p>
     *
     * @param message the raw message content to analyze
     * @return the detected Protocol enum value
     */
    public static Protocol detect(String message) {
        if (message == null || message.isEmpty()) {
            return Protocol.UNKNOWN;
        }

        // Remove leading whitespace for cleaner detection
        String trimmed = message.trim();

        // ASTM detection: STX (0x02) or "H|\^&" header
        if (!trimmed.isEmpty() && trimmed.charAt(0) == 0x02) {
            return Protocol.ASTM;
        }
        if (trimmed.startsWith("H|\\^&")) {
            return Protocol.ASTM;
        }

        // HL7 detection: MSH| segment
        if (trimmed.startsWith("MSH|")) {
            return Protocol.HL7;
        }

        // CSV detection: contains commas and first line has multiple comma-separated values
        if (trimmed.contains(",")) {
            String firstLine = trimmed.split("\n")[0];
            String[] columns = firstLine.split(",");
            if (columns.length > 3) {  // Require at least 4 columns to be considered CSV
                return Protocol.CSV;
            }
        }

        return Protocol.UNKNOWN;
    }

    /**
     * Checks if the message is a valid ASTM format.
     *
     * @param message the message to check
     * @return true if message appears to be ASTM format
     */
    public static boolean isASTM(String message) {
        return detect(message) == Protocol.ASTM;
    }

    /**
     * Checks if the message is a valid HL7 format.
     *
     * @param message the message to check
     * @return true if message appears to be HL7 format
     */
    public static boolean isHL7(String message) {
        return detect(message) == Protocol.HL7;
    }

    /**
     * Checks if the message is a valid CSV format.
     *
     * @param message the message to check
     * @return true if message appears to be CSV format
     */
    public static boolean isCSV(String message) {
        return detect(message) == Protocol.CSV;
    }

    private ProtocolDetector() {
        // Utility class - prevent instantiation
    }
}
