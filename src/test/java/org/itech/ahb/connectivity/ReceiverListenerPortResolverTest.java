package org.itech.ahb.connectivity;

import static org.assertj.core.api.Assertions.assertThat;

import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.mllp.MLLPConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiverListenerPortResolverTest {

  private ReceiverListenerPortResolver resolver;

  @BeforeEach
  void setUp() {
    ASTMLIS1AListenServerConfigurationProperties lis1 = new ASTMLIS1AListenServerConfigurationProperties();
    lis1.setPort(13001);
    ASTME138195ListenServerConfigurationProperties e1381 =
      new ASTME138195ListenServerConfigurationProperties();
    e1381.setPort(13011);
    MLLPConfig mllp = new MLLPConfig();
    mllp.setPort(3575);
    resolver = new ReceiverListenerPortResolver(lis1, e1381, mllp);
  }

  @Test
  void resolvesAstmListenerFromThePinnedProfilesLowerLayer() {
    assertThat(resolver.resolve(receiver("TCP", "ASTM", "LIS01_A"))).hasValue(13001);
    assertThat(resolver.resolve(receiver("TCP", "ASTM", "E1381_95"))).hasValue(13011);
  }

  @Test
  void resolvesMllpFromBridgeRuntimeConfiguration() {
    assertThat(resolver.resolve(receiver("MLLP", "HL7", null))).hasValue(3575);
  }

  @Test
  void rejectsAReceiverWhosePinnedProfileDoesNotIdentifyItsListener() {
    assertThat(resolver.resolve(receiver("TCP", "ASTM", null))).isEmpty();
    assertThat(resolver.resolve(receiver("TCP", "HL7", "LIS01_A"))).isEmpty();
  }

  private static AnalyzerEntry receiver(String mode, String protocol, String lowerLayerVersion) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setConnectionRole("RECEIVER");
    entry.setConnectionMode(mode);
    entry.setExpectedProtocol(protocol);
    entry.setProfileLowerLayerVersion(lowerLayerVersion);
    return entry;
  }
}
