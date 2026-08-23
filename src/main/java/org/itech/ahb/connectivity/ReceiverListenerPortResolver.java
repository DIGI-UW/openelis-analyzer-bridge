package org.itech.ahb.connectivity;

import java.util.OptionalInt;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.mllp.MLLPConfig;
import org.springframework.stereotype.Component;

/** Resolves receiver endpoints from the pinned profile and live Bridge configuration. */
@Component
public final class ReceiverListenerPortResolver {

  private final ASTMLIS1AListenServerConfigurationProperties lis1;
  private final ASTME138195ListenServerConfigurationProperties e1381;
  private final MLLPConfig mllp;

  public ReceiverListenerPortResolver(
    ASTMLIS1AListenServerConfigurationProperties lis1,
    ASTME138195ListenServerConfigurationProperties e1381,
    MLLPConfig mllp
  ) {
    this.lis1 = lis1;
    this.e1381 = e1381;
    this.mllp = mllp;
  }

  public OptionalInt resolve(AnalyzerEntry analyzer) {
    if (!"RECEIVER".equals(analyzer.getConnectionRole())) {
      return OptionalInt.empty();
    }
    if ("MLLP".equals(analyzer.getConnectionMode()) && "HL7".equals(analyzer.getExpectedProtocol())) {
      return valid(mllp.getPort());
    }
    if (!"TCP".equals(analyzer.getConnectionMode()) || !"ASTM".equals(analyzer.getExpectedProtocol())) {
      return OptionalInt.empty();
    }
    return switch (String.valueOf(analyzer.getProfileLowerLayerVersion())) {
      case "LIS01_A" -> valid(lis1.getPort());
      case "E1381_95" -> valid(e1381.getPort());
      default -> OptionalInt.empty();
    };
  }

  private static OptionalInt valid(int port) {
    return port > 0 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
  }
}
