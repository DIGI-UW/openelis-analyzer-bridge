package org.itech.ahb.connection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment fallbacks used only when a pinned profile has no outbound destination port. */
@Data
@ConfigurationProperties(prefix = "bridge.outbound-defaults")
public class AnalyzerOutboundDefaults {

  private int astmPort = 12001;
  private int hl7Port = 2575;
}
