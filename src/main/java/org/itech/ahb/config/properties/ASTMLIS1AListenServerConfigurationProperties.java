package org.itech.ahb.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the ASTM LIS1-A listen server which listens for ASTM messages compliant with LIS1A.
 * Then it forwards the ASTM messages to the configured HTTP server.
 * LIS1A is equivalent to E1381-02.
 */
@ConfigurationProperties(prefix = "org.itech.ahb.listen-astm-server")
@Data
public class ASTMLIS1AListenServerConfigurationProperties {

  /**
   * The port on which the server listens.
   */
  private int port = 12001;
}
