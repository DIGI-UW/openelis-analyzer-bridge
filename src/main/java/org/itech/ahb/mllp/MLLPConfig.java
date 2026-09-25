package org.itech.ahb.mllp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Enables the shared MLLP (Minimal Lower Layer Protocol) listener on {@link #port}.
 * Saved HL7 server connections share this deployment endpoint.
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
   * Whether saved HL7 server connections may start MLLP listeners.
   * Defaults to false for safety; production deployments should explicitly enable
   * via the MLLP_ENABLED environment variable.
   */
  private boolean enabled = false;

  /**
   * The shared MLLP port bound at boot when enabled. Default is 2575 (standard HL7 MLLP port).
   * Saved HL7 server connections join this listener without an analyzer-level port.
   */
  private int port = 2575;
}
