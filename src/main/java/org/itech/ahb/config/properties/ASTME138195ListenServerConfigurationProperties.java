package org.itech.ahb.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the ASTM E1381-95 listen server which listens for ASTM messages compliant with E1381-95.
 * Then it forwards the ASTM messages to the configured HTTP server.
 */
@ConfigurationProperties(prefix = "org.itech.ahb.listen-astm-server.e1381-95")
@Data
public class ASTME138195ListenServerConfigurationProperties {

  /**
   * The port on which the server listens.
   */
  private int port = 12011;
}
