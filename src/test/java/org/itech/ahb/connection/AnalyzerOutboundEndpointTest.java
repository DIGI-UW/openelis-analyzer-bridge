package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure resolution guards; saved catalog and socket tests cover persistence and transport execution. */
class AnalyzerOutboundEndpointTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7" })
  void overrideProfileAndDeploymentFallbackHaveExplicitPrecedence(String protocol) {
    AnalyzerOutboundDefaults defaults = new AnalyzerOutboundDefaults();
    defaults.setAstmPort(7101);
    defaults.setHl7Port(7102);
    AnalyzerOutboundEndpoint resolver = new AnalyzerOutboundEndpoint(defaults);
    ObjectNode profile = profile(protocol);
    ObjectNode values = values("SERVER");
    values.put("port", 1200); // A historical incoming port must never be an outbound candidate.
    assertThat(resolver.resolvePort(profile, values).port()).isEqualTo("ASTM".equals(protocol) ? 7101 : 7102);
    assertThat(resolver.resolvePort(profile, values).source()).isEqualTo("Bridge default");
    profile.withObject("transport_config").withObject("TCP/IP").put("default_port", 6100);
    assertThat(resolver.resolvePort(profile, values).port()).isEqualTo(6100);
    assertThat(resolver.resolvePort(profile, values).source()).isEqualTo("Profile default");
    values.put("outboundPort", 6200);
    assertThat(resolver.resolvePort(profile, values).port()).isEqualTo(6200);
    assertThat(resolver.resolvePort(profile, values).source()).isEqualTo("Connection override");
    values.put("outboundPortMode", "DEFAULT");
    assertThat(resolver.resolvePort(profile, values).port()).isEqualTo(6100);
    assertThat(values.path("outboundPort").asInt()).isEqualTo(6200);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, -1, 65536 })
  void invalidOverridesDoNotSilentlyFallBack(int invalid) {
    AnalyzerOutboundEndpoint resolver = new AnalyzerOutboundEndpoint(new AnalyzerOutboundDefaults());
    ObjectNode values = values("CLIENT").put("outboundPort", invalid);
    assertThatThrownBy(() -> resolver.resolvePort(profile("ASTM"), values))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("Connection override");
  }

  @Test
  void fractionalOrOversizedPortsAreNotTruncated() {
    AnalyzerOutboundEndpoint resolver = new AnalyzerOutboundEndpoint(new AnalyzerOutboundDefaults());
    ObjectNode values = values("CLIENT").put("port", 6001.5);
    assertThatThrownBy(() -> resolver.resolvePort(profile("ASTM"), values)).isInstanceOf(
      AnalyzerConnectionException.class
    );
    values.put("port", 4294967297L);
    assertThatThrownBy(() -> resolver.resolvePort(profile("ASTM"), values)).isInstanceOf(
      AnalyzerConnectionException.class
    );
  }

  @Test
  void resultsOnlyDoesNotEnableIndependentOutboundOrders() {
    ObjectNode profile = profile("ASTM");
    profile.putObject("capabilities").put("outboundOrders", true);
    profile.putObject("communication").put("supports_lis_initiated", true);
    ObjectNode values = values("SERVER").put("dataFlow", "RESULTS_ONLY");
    assertThat(AnalyzerOutboundEndpoint.ordersEnabled(profile, values)).isFalse();
    assertThat(AnalyzerOutboundEndpoint.needed(profile, values)).isFalse();
    values.put("dataFlow", "TWO_WAY");
    assertThat(AnalyzerOutboundEndpoint.ordersEnabled(profile, values)).isTrue();
    assertThat(AnalyzerOutboundEndpoint.needed(profile, values)).isTrue();
    values.remove("dataFlow");
    assertThat(AnalyzerOutboundEndpoint.ordersEnabled(profile, values))
      .as("preserve older profile capability semantics")
      .isTrue();
  }

  private ObjectNode profile(String protocol) {
    ObjectNode profile = mapper.createObjectNode();
    profile.putObject("protocol").put("name", protocol);
    return profile;
  }

  private ObjectNode values(String role) {
    return mapper.createObjectNode().put("transport", "TCP/IP").put("connectionRole", role);
  }
}
