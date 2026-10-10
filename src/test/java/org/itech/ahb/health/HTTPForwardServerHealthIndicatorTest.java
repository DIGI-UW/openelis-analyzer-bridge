package org.itech.ahb.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Forwarding health reads OpenELIS's health under the same base URL results are sent to. */
class HTTPForwardServerHealthIndicatorTest {

  private HttpServer server;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/OpenELIS-Global/health", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private Health healthFor(String uri) {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create(uri));
    config.setConnectTimeoutSeconds(2);
    config.setReadTimeoutSeconds(2);
    return new HTTPForwardServerHealthIndicator(config).health();
  }

  @Test
  @DisplayName("UP when OpenELIS answers its health under the base URL")
  void upWhenOpenElisAnswers() {
    Health health = healthFor(base + "/OpenELIS-Global");

    assertEquals(Status.UP, health.getStatus());
    assertEquals(base + "/OpenELIS-Global/health", health.getDetails().get("probed"));
  }

  @Test
  @DisplayName("DOWN when the base URL is not OpenELIS's, naming the URL it checked")
  void downWhenTheBaseUrlIsWrong() {
    Health health = healthFor(base + "/OpenELIS-Global/analyzer");

    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(base + "/OpenELIS-Global/analyzer/health", health.getDetails().get("probed"));
  }
}
