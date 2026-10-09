package org.itech.ahb.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class HTTPForwardServerHealthIndicatorTest {

  private HttpServer openElis;

  @AfterEach
  void stop() {
    if (openElis != null) openElis.stop(0);
  }

  @Test
  @DisplayName("the probe goes to the health endpoint under the same base results are delivered to")
  void probesHealthUnderTheDeliveryBase() throws Exception {
    AtomicReference<String> probed = new AtomicReference<>();
    openElis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    openElis.createContext("/", exchange -> {
      probed.set(exchange.getRequestURI().getPath());
      exchange.sendResponseHeaders("/api/OpenELIS-Global/health".equals(probed.get()) ? 200 : 404, -1);
      exchange.close();
    });
    openElis.start();
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + openElis.getAddress().getPort() + "/api/OpenELIS-Global"));

    Health health = new HTTPForwardServerHealthIndicator(config).health();

    assertThat(probed.get()).isEqualTo("/api/OpenELIS-Global/health");
    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("a refused forwarding URI is DOWN with the reason, without probing anything")
  void aRefusedForwardingUriIsDown() {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:1/api/OpenELIS-Global/analyzer"));

    Health health = new HTTPForwardServerHealthIndicator(config).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "forwarding_url_refused");
    assertThat((String) health.getDetails().get("message")).contains("org.itech.ahb.forward-http-server.uri");
  }
}
