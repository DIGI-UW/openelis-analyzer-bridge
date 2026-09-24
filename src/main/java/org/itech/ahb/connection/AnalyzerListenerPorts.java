package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.mllp.MLLPConfig;

/** Resolves shared inbound listeners from deployment settings, never analyzer connection values. */
public final class AnalyzerListenerPorts {

  private final ASTMLIS1AListenServerConfigurationProperties lis1a;
  private final ASTME138195ListenServerConfigurationProperties e138195;
  private final MLLPConfig mllp;

  public AnalyzerListenerPorts(
    ASTMLIS1AListenServerConfigurationProperties lis1a,
    ASTME138195ListenServerConfigurationProperties e138195,
    MLLPConfig mllp
  ) {
    this.lis1a = lis1a;
    this.e138195 = e138195;
    this.mllp = mllp;
  }

  public static AnalyzerListenerPorts defaults() {
    return new AnalyzerListenerPorts(
      new ASTMLIS1AListenServerConfigurationProperties(),
      new ASTME138195ListenServerConfigurationProperties(),
      new MLLPConfig()
    );
  }

  public int forProfile(JsonNode profile) {
    JsonNode protocol = profile.path("protocol");
    int port =
      switch (protocol.path("name").asText()) {
        case "HL7" -> mllp.getPort();
        case "ASTM" -> switch (protocol.path("lowerLayerVersion").asText()) {
          case "LIS01_A" -> lis1a.getPort();
          case "E1381_95" -> e138195.getPort();
          default -> throw new AnalyzerConnectionException(
            "Unsupported ASTM lower-layer version " + protocol.path("lowerLayerVersion").asText()
          );
        };
        default -> throw new AnalyzerConnectionException(
          "No shared listener for protocol " + protocol.path("name").asText()
        );
      };
    if (port < 1 || port > 65535) {
      throw new AnalyzerConnectionException("The deployment shared listener port must be between 1 and 65535");
    }
    return port;
  }
}
