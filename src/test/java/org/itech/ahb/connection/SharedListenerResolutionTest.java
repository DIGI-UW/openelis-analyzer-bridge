package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.Resolution;
import org.junit.jupiter.api.Test;

/**
 * The resolution ladder for messages that arrive on a shared listener: peer address, then
 * uniqueness, and otherwise a dead letter. It must never attribute a message to a connection whose
 * saved address is a different machine, and never pick between two it cannot tell apart.
 */
class SharedListenerResolutionTest {

  private static final int SHARED_PORT = 12001;

  private final AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();

  @Test
  void singleConnectionWithoutAHostOwnsItsListenerByUniqueness() {
    register("gx-a", SHARED_PORT, null);

    Resolution resolution = registry.resolve(SHARED_PORT, "10.0.0.21", "GENEXPERT^GeneXpert^4.6.0");

    assertResolved(resolution, "gx-a", "uniqueness");
  }

  @Test
  void peerAddressSeparatesTwoConnectionsOnOneListener() {
    register("gx-a", SHARED_PORT, "10.0.0.21");
    register("gx-b", SHARED_PORT, "10.0.0.22");

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", null), "gx-a", "address");
    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.22", null), "gx-b", "address");
  }

  @Test
  void peerAddressIsComparedInCanonicalForm() {
    register("gx-a", SHARED_PORT, "2001:db8:0:0:0:0:0:21");
    register("gx-b", SHARED_PORT, "10.0.0.22");

    assertResolved(registry.resolve(SHARED_PORT, "2001:db8::21", null), "gx-a", "address");
  }

  @Test
  void connectionWithADifferentLiteralHostIsNeverACandidate() {
    register("gx-a", SHARED_PORT, "10.0.0.21");
    register("gx-open", SHARED_PORT, null);

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.99", null), "gx-open", "uniqueness");
  }

  @Test
  void unknownAddressIsUnregisteredWhenEveryConnectionNamesAnotherHost() {
    register("gx-a", SHARED_PORT, "10.0.0.21");
    register("gx-b", SHARED_PORT, "10.0.0.22");

    Resolution resolution = registry.resolve(SHARED_PORT, "10.0.0.99", null);

    assertThat(resolution).isInstanceOf(Resolution.Unregistered.class);
    assertThat(((Resolution.Unregistered) resolution).detail()).contains("port 12001").contains("10.0.0.99");
  }

  @Test
  void twoConnectionsWithoutHostsAreAmbiguousAndNamedInTheDetail() {
    register("gx-a", SHARED_PORT, null);
    register("gx-b", SHARED_PORT, null);

    Resolution resolution = registry.resolve(SHARED_PORT, "10.0.0.21", "GENEXPERT^GeneXpert^4.6.0");

    assertThat(resolution).isInstanceOf(Resolution.Ambiguous.class);
    Resolution.Ambiguous ambiguous = (Resolution.Ambiguous) resolution;
    assertThat(ambiguous.connectionIds()).containsExactly("gx-a", "gx-b");
    assertThat(ambiguous.detail()).contains("gx-a").contains("gx-b").contains("host").contains("senderId");
  }

  @Test
  void twoConnectionsBehindOneAddressAreAmbiguous() {
    register("gx-a", SHARED_PORT, "10.0.0.21");
    register("gx-b", SHARED_PORT, "10.0.0.21");

    assertThat(registry.resolve(SHARED_PORT, "10.0.0.21", null)).isInstanceOf(Resolution.Ambiguous.class);
  }

  @Test
  void connectionsOnOtherListenersAreNotCandidates() {
    register("gx-a", SHARED_PORT, null);
    register("gx-dedicated", 9600, null);

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", null), "gx-a", "uniqueness");
    assertResolved(registry.resolve(9600, "10.0.0.21", null), "gx-dedicated", "uniqueness");
  }

  @Test
  void listenerWithNoActiveConnectionIsUnregistered() {
    register("gx-dedicated", 9600, null);

    Resolution resolution = registry.resolve(SHARED_PORT, "10.0.0.21", null);

    assertThat(resolution).isInstanceOf(Resolution.Unregistered.class);
    assertThat(((Resolution.Unregistered) resolution).detail()).contains("port 12001");
  }

  @Test
  void messagesWithoutAListenerKeepTheDirectSourceLookup() {
    AnalyzerEntry serial = entry("serial-a", null, null);
    registry.register("/dev/ttyUSB0", serial);
    AnalyzerEntry stored = entry("gx-legacy", null, null);
    registry.register("connection:gx-legacy", stored);

    assertResolved(registry.resolve(null, "/dev/ttyUSB0", null), "serial-a", "source");
    // Outbox rows written by 3.1.x carry the connection key as their source and still resolve on retry.
    assertResolved(registry.resolve(null, "connection:gx-legacy", null), "gx-legacy", "source");
    assertThat(registry.resolve(null, "/dev/ttyUSB9", null)).isInstanceOf(Resolution.Unregistered.class);
  }

  @Test
  void senderNameSeparatesTwoConnectionsWithoutHosts() {
    registerNamed("gx-a", null, "GX-LAB-A", null);
    registerNamed("gx-b", null, "GX-LAB-B", null);

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", "GX-LAB-A^GeneXpert^6.2"), "gx-a", "sender");
    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", "gx-lab-b^GeneXpert^6.2"), "gx-b", "sender");
  }

  @Test
  void senderNameSeparatesTwoConnectionsBehindOneAddress() {
    registerNamed("gx-a", "10.0.0.21", "GX-LAB-A", null);
    registerNamed("gx-b", "10.0.0.21", "GX-LAB-B", null);

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", "GX-LAB-B^GeneXpert^6.2"), "gx-b", "sender");
  }

  @Test
  void aConnectionNamingAnotherInstrumentIsNeverACandidate() {
    registerNamed("gx-a", null, "GX-LAB-A", null);
    registerNamed("gx-open", null, null, null);

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", "GX-LAB-C^GeneXpert^6.2"), "gx-open", "uniqueness");
  }

  @Test
  void aSenderNoConnectionNamesIsUnregisteredWhenEveryConnectionIsNamed() {
    registerNamed("gx-a", null, "GX-LAB-A", null);
    registerNamed("gx-b", null, "GX-LAB-B", null);

    Resolution resolution = registry.resolve(SHARED_PORT, "10.0.0.21", "GX-LAB-C^GeneXpert^6.2");

    assertThat(resolution).isInstanceOf(Resolution.Unregistered.class);
    assertThat(((Resolution.Unregistered) resolution).detail()).contains("GX-LAB-C");
  }

  @Test
  void profilePatternRulesOutAnotherKindOfAnalyzer() {
    registerNamed("gx-a", null, null, "GENEXPERT|CEPHEID");
    registerNamed("bs-a", null, null, "MINDRAY|BS-");

    assertResolved(registry.resolve(SHARED_PORT, "10.0.0.21", "MINDRAY^BS-200^1.0"), "bs-a", "uniqueness");
  }

  @Test
  void profilePatternNeverPicksBetweenTwoAnalyzersOfTheSameKind() {
    registerNamed("gx-a", null, null, "GENEXPERT|CEPHEID");
    registerNamed("gx-b", null, null, "GENEXPERT|CEPHEID");

    assertThat(registry.resolve(SHARED_PORT, "10.0.0.21", "GENEXPERT^GeneXpert^4.6.0"))
      .isInstanceOf(Resolution.Ambiguous.class);
  }

  @Test
  void pairsAreIndistinguishableOnlyWhenNeitherAddressNorSenderSeparatesThem() {
    AnalyzerEntry hostless = registerNamed("gx-a", null, null, null);

    assertThat(registry.indistinguishableFrom(named("gx-b", null, null, null))).isSameAs(hostless);
    assertThat(registry.indistinguishableFrom(named("gx-b", null, "GX-LAB-B", null))).isNull();
    assertThat(registry.indistinguishableFrom(named("gx-b", "10.0.0.22", null, null))).isNull();
    assertThat(registry.indistinguishableFrom(named("gx-a", null, null, null)))
      .as("a connection is never its own twin")
      .isNull();

    registry.unregister("connection:gx-a", "oe-gx-a");
    AnalyzerEntry named = registerNamed("gx-a", null, "GX-LAB-A", null);
    assertThat(registry.indistinguishableFrom(named("gx-b", null, "gx-lab-a", null))).isSameAs(named);
    assertThat(registry.indistinguishableFrom(named("gx-b", null, null, null)))
      .as("one unnamed connection beside named ones is still separable")
      .isNull();
  }

  private AnalyzerEntry registerNamed(String connectionId, String address, String senderId, String pattern) {
    AnalyzerEntry entry = named(connectionId, address, senderId, pattern);
    registry.register("connection:" + connectionId, entry);
    return entry;
  }

  private static AnalyzerEntry named(String connectionId, String address, String senderId, String pattern) {
    AnalyzerEntry entry = entry(connectionId, SHARED_PORT, address);
    entry.setSenderId(senderId);
    entry.setIdentifierPattern(pattern);
    return entry;
  }

  private void register(String connectionId, int listenerPort, String address) {
    registry.register("connection:" + connectionId, entry(connectionId, listenerPort, address));
  }

  private static AnalyzerEntry entry(String connectionId, Integer listenerPort, String address) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("oe-" + connectionId);
    entry.setBridgeConnectionId(connectionId);
    entry.setExpectedProtocol("ASTM");
    entry.setInboundTransport("TCP/IP");
    entry.setListenerPort(listenerPort);
    entry.setInboundAddress(address == null ? null : org.itech.ahb.util.IpLiteral.canonicalize(address));
    entry.setInboundSourceId(entry.getInboundAddress());
    return entry;
  }

  private static void assertResolved(Resolution resolution, String connectionId, String basis) {
    assertThat(resolution).isInstanceOf(Resolution.Resolved.class);
    Resolution.Resolved resolved = (Resolution.Resolved) resolution;
    assertThat(resolved.entry().getBridgeConnectionId()).isEqualTo(connectionId);
    assertThat(resolved.basis()).isEqualTo(basis);
  }
}
