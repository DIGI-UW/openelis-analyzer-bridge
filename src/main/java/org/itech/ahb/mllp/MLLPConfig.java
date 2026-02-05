package org.itech.ahb.mllp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MLLP (Minimal Lower Layer Protocol) server.
 * <p>
 * MLLP is the standard transport layer for HL7 v2.x messages over TCP.
 * It uses specific framing characters:
 * <ul>
 *   <li>Start Block: VT (0x0B / ASCII 11)</li>
 *   <li>End Block: FS (0x1C / ASCII 28) followed by CR (0x0D / ASCII 13)</li>
 * </ul>
 * </p>
 */
@ConfigurationProperties(prefix = "org.itech.ahb.mllp")
@Data
public class MLLPConfig {

    /**
     * Whether the MLLP server is enabled.
     * Defaults to false for safety; production deployments should explicitly enable
     * via the MLLP_ENABLED environment variable.
     */
    private boolean enabled = false;

    /**
     * The port on which the MLLP server listens.
     * Default is 2575 (standard HL7 MLLP port).
     */
    private int port = 2575;

    /**
     * Socket timeout in milliseconds for reading from connections.
     * Default is 30000ms (30 seconds).
     */
    private int timeout = 30000;

    /**
     * Maximum message size in bytes.
     * Default is 1MB (1048576 bytes).
     */
    private int maxMessageSize = 1048576;
}
