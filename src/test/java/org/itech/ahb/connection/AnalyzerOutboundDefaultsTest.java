package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Spring property binding; real destination sockets are exercised by OutboundConnectionProbeTest. */
class AnalyzerOutboundDefaultsTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AnalyzerOutboundDefaults.class)
  static class BoundDefaults {}

  @Test
  void deploymentConfigurationSuppliesIndependentProtocolFallbacks() {
    new ApplicationContextRunner()
      .withUserConfiguration(BoundDefaults.class)
      .withPropertyValues("bridge.outbound-defaults.astm-port=14901", "bridge.outbound-defaults.hl7-port=14575")
      .run(context -> {
        assertThat(context).hasNotFailed();
        AnalyzerOutboundEndpoint resolver = new AnalyzerOutboundEndpoint(
          context.getBean(AnalyzerOutboundDefaults.class)
        );
        ObjectMapper mapper = new ObjectMapper();
        var values = mapper.createObjectNode().put("transport", "TCP/IP").put("connectionRole", "CLIENT");
        var profile = mapper.createObjectNode();
        profile.putObject("protocol").put("name", "ASTM");
        assertThat(resolver.resolvePort(profile, values).port()).isEqualTo(14901);
        profile.withObject("protocol").put("name", "HL7");
        assertThat(resolver.resolvePort(profile, values).port()).isEqualTo(14575);
      });
  }
}
