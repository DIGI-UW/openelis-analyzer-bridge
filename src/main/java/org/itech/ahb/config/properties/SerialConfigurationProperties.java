package org.itech.ahb.config.properties;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for serial port listeners.
 * <p>
 * Supports multiple serial ports with individual configurations for baud rate,
 * data bits, stop bits, parity, and protocol detection mode.
 * </p>
 *
 * Example configuration:
 * <pre>
 * org.itech.ahb.serial:
 *   enabled: true
 *   ports:
 *     - path: /dev/ttyUSB0
 *       baud-rate: 9600
 *       data-bits: 8
 *       stop-bits: 1
 *       parity: NONE
 *       protocol: AUTO
 *       analyzer-id: HORIBA-PENTRA60-001
 *     - path: /dev/ttyUSB1
 *       baud-rate: 19200
 *       protocol: ASTM
 * </pre>
 */
@ConfigurationProperties(prefix = "org.itech.ahb.serial")
@Data
public class SerialConfigurationProperties {

    /**
     * Whether serial port listeners are enabled.
     */
    private boolean enabled = false;

    /**
     * List of serial port configurations.
     */
    private List<SerialPortConfig> ports = new ArrayList<>();

    /**
     * Default timeout in milliseconds for waiting for complete messages.
     * If no data received for this duration, the buffer is cleared.
     */
    private int messageTimeoutMs = 30000;

    /**
     * Interval in milliseconds for checking serial port availability.
     * Used for reconnection attempts when a port becomes unavailable.
     */
    private int reconnectIntervalMs = 5000;

    /**
     * Maximum number of reconnection attempts before giving up.
     * Set to -1 for unlimited attempts.
     */
    private int maxReconnectAttempts = -1;

    /**
     * Configuration for a single serial port.
     */
    @Data
    public static class SerialPortConfig {
        /**
         * Serial port path (e.g., /dev/ttyUSB0, COM1).
         */
        private String path;

        /**
         * Baud rate for serial communication.
         * Common values: 9600, 19200, 38400, 57600, 115200
         */
        private int baudRate = 9600;

        /**
         * Number of data bits per frame.
         * Valid values: 5, 6, 7, 8
         */
        private int dataBits = 8;

        /**
         * Number of stop bits.
         * Valid values: 1, 2 (or 1.5 represented as 3)
         */
        private int stopBits = 1;

        /**
         * Parity mode for error detection.
         */
        private Parity parity = Parity.NONE;

        /**
         * Protocol detection mode.
         * AUTO will detect ASTM vs HL7 from message content.
         */
        private ProtocolMode protocol = ProtocolMode.AUTO;

        /**
         * Flow control mode.
         */
        private FlowControl flowControl = FlowControl.NONE;

        /**
         * Optional analyzer ID for this port.
         * If not set, will attempt to extract from message headers.
         */
        private String analyzerId;

        /**
         * Human-readable name for this port (for logging).
         */
        private String name;

        /**
         * Read timeout in milliseconds.
         * How long to wait for data before timing out.
         */
        private int readTimeoutMs = 1000;

        /**
         * Whether to enable RTS (Request to Send) signal.
         */
        private boolean rtsEnabled = true;

        /**
         * Whether to enable DTR (Data Terminal Ready) signal.
         */
        private boolean dtrEnabled = true;
    }

    /**
     * Parity modes for serial communication.
     */
    public enum Parity {
        NONE,
        ODD,
        EVEN,
        MARK,
        SPACE
    }

    /**
     * Protocol detection modes.
     */
    public enum ProtocolMode {
        /**
         * Auto-detect protocol from message content.
         * Uses ProtocolDetector to identify ASTM vs HL7.
         */
        AUTO,
        /**
         * Expect only ASTM LIS2-A2 protocol.
         * Uses ASTM framing (ENQ/ACK/STX/ETX/EOT).
         */
        ASTM,
        /**
         * Expect only HL7 protocol.
         * Uses MLLP framing (VT/FS/CR).
         */
        HL7
    }

    /**
     * Flow control modes.
     */
    public enum FlowControl {
        NONE,
        RTS_CTS,
        XON_XOFF
    }
}
