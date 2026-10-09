package org.itech.ahb.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HTTPForwardServerConfigurationPropertiesTest {

  private static HTTPForwardServerConfigurationProperties base(String uri) {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create(uri));
    return config;
  }

  @Test
  @DisplayName("an OpenELIS base under a context path delivers to its analyzer endpoint, not the FHIR server")
  void aContextPathBaseDeliversToTheAnalyzerEndpoint() {
    HTTPForwardServerConfigurationProperties config = base("https://lab.example.org/api/OpenELIS-Global");

    assertThat(config.deliveryUri()).hasToString("https://lab.example.org/api/OpenELIS-Global/analyzer/fhir");
    assertThat(config.healthCheckUri()).hasToString("https://lab.example.org/api/OpenELIS-Global/health");
    assertThat(config.refusal()).isEmpty();
  }

  @Test
  @DisplayName("a trailing slash on the base changes nothing")
  void aTrailingSlashChangesNothing() {
    HTTPForwardServerConfigurationProperties config = base("https://oe.openelis.org:8443/OpenELIS-Global/");

    assertThat(config.deliveryUri()).hasToString("https://oe.openelis.org:8443/OpenELIS-Global/analyzer/fhir");
    assertThat(config.healthCheckUri()).hasToString("https://oe.openelis.org:8443/OpenELIS-Global/health");
  }

  @Test
  @DisplayName("a base with no path delivers at the root")
  void aBaseWithNoPathDeliversAtTheRoot() {
    HTTPForwardServerConfigurationProperties config = base("http://127.0.0.1:8080");

    assertThat(config.deliveryUri()).hasToString("http://127.0.0.1:8080/analyzer/fhir");
    assertThat(config.healthCheckUri()).hasToString("http://127.0.0.1:8080/health");
    assertThat(config.refusal()).isEmpty();
  }

  @Test
  @DisplayName("a forwarding URI ending in /analyzer is refused, naming the base to set instead")
  void theOldAnalyzerFormIsRefused() {
    HTTPForwardServerConfigurationProperties config = base("https://oe.openelis.org:8443/OpenELIS-Global/analyzer/");

    assertThat(config.refusal())
      .hasValueSatisfying(message ->
        assertThat(message)
          .contains("org.itech.ahb.forward-http-server.uri")
          .contains("https://oe.openelis.org:8443/OpenELIS-Global/analyzer/")
          .contains("set it to https://oe.openelis.org:8443/OpenELIS-Global")
      );
  }

  @Test
  @DisplayName("any health-uri is refused: the health check follows the base")
  void aSeparateHealthUriIsRefused() {
    HTTPForwardServerConfigurationProperties config = base("https://lab.example.org/api/OpenELIS-Global");
    config.setHealthUri(URI.create("https://lab.example.org/api/OpenELIS-Global/health"));

    assertThat(config.refusal())
      .hasValueSatisfying(message ->
        assertThat(message).contains("org.itech.ahb.forward-http-server.health-uri").contains("remove it")
      );
  }
}
