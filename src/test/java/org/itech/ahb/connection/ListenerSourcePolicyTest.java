package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.util.IpLiteral;
import org.junit.jupiter.api.Test;

class ListenerSourcePolicyTest {

  private final AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();

  private static InetAddress at(String address) throws Exception {
    return InetAddress.getByName(address);
  }

  @Test
  void withNothingConfiguredEveryPeerIsAdmitted() throws Exception {
    ListenerSourcePolicy policy = new ListenerSourcePolicy("", false, registry);

    assertThat(policy.admits(12001, at("203.0.113.7"))).isTrue();
  }

  @Test
  void anAllowlistAdmitsOnlyItsAddressesAndRanges() throws Exception {
    ListenerSourcePolicy policy = new ListenerSourcePolicy("10.20.0.0/16, 192.0.2.5 ,2001:db8::/32", false, registry);

    assertThat(policy.admits(12001, at("10.20.31.4"))).isTrue();
    assertThat(policy.admits(12001, at("192.0.2.5"))).isTrue();
    assertThat(policy.admits(2575, at("2001:db8:0:0:0:0:0:9"))).isTrue();
    assertThat(policy.admits(12001, at("10.21.0.1"))).isFalse();
    assertThat(policy.admits(12001, at("192.0.2.6"))).isFalse();
    assertThat(policy.admits(12001, at("2001:db9::1"))).isFalse();
  }

  @Test
  void anAllowlistWithNoUsableEntryRefusesRatherThanOpeningUp() throws Exception {
    ListenerSourcePolicy policy = new ListenerSourcePolicy("lab-router.local, 10.0.0.0/40", false, registry);

    assertThat(policy.admits(12001, at("10.0.0.1"))).isFalse();
  }

  @Test
  void knownHostsOnlyAdmitsTheHostsOfActiveConnectionsOnThatListener() throws Exception {
    register("gx", 12001, "10.20.31.4");
    register("mindray", 2575, "10.20.31.9");
    ListenerSourcePolicy policy = new ListenerSourcePolicy("", true, registry);

    assertThat(policy.admits(12001, at("10.20.31.4"))).isTrue();
    assertThat(policy.admits(12001, at("10.20.31.9"))).as("a host on another listener").isFalse();
    assertThat(policy.admits(12001, at("10.20.31.5"))).isFalse();
  }

  @Test
  void bothSettingsMustAdmitAPeer() throws Exception {
    register("gx", 12001, "10.20.31.4");
    ListenerSourcePolicy policy = new ListenerSourcePolicy("192.0.2.0/24", true, registry);

    assertThat(policy.admits(12001, at("10.20.31.4"))).isFalse();
  }

  private void register(String connectionId, int listenerPort, String address) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("oe-" + connectionId);
    entry.setBridgeConnectionId(connectionId);
    entry.setListenerPort(listenerPort);
    entry.setInboundAddress(IpLiteral.canonicalize(address));
    registry.register("connection:" + connectionId, entry);
  }
}
