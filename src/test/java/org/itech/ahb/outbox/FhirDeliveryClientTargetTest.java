package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The forwarding URI is OpenELIS's base URL; the result endpoint is derived from it. */
class FhirDeliveryClientTargetTest {

  private static FhirDeliveryClient clientFor(String uri) {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create(uri));
    return new FhirDeliveryClient(config);
  }

  @Test
  @DisplayName("results go to the base URL's analyzer endpoint")
  void resultsGoToTheAnalyzerEndpoint() {
    assertEquals(
      URI.create("https://oe.example:8443/OpenELIS-Global/analyzer/fhir"),
      clientFor("https://oe.example:8443/OpenELIS-Global").targetUri()
    );
  }

  @Test
  @DisplayName("a trailing slash on the base URL makes no difference")
  void aTrailingSlashMakesNoDifference() {
    assertEquals(
      URI.create("https://oe.example:8443/OpenELIS-Global/analyzer/fhir"),
      clientFor("https://oe.example:8443/OpenELIS-Global/").targetUri()
    );
  }
}
