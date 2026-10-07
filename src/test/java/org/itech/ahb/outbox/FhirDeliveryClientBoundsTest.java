package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The configured OpenELIS endpoint, or whoever answers in its place, cannot exhaust the bridge. */
class FhirDeliveryClientBoundsTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  private FhirDeliveryClient clientFor(String path, int readTimeoutSeconds, int maxResponseBytes) {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path));
    config.setConnectTimeoutSeconds(2);
    config.setReadTimeoutSeconds(readTimeoutSeconds);
    config.setMaxResponseBytes(maxResponseBytes);
    return new FhirDeliveryClient(config);
  }

  @Test
  @DisplayName("an oversized response is a failed attempt, read no further than the limit")
  void anOversizedResponseFailsTheAttempt() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] chunk = new byte[64 * 1024];
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream body = exchange.getResponseBody()) {
        for (int i = 0; i < 64; i++) body.write(chunk);
      } catch (IOException closedByClient) {
        // The client stops reading at its limit.
      }
    });
    server.start();

    DeliveryOutcome outcome = clientFor("/analyzer", 5, 1024 * 1024).deliver("{}");

    assertFalse(outcome.reachedOpenElis());
    assertTrue(outcome.describeFailure().contains("exceeded"), outcome.describeFailure());
  }

  @Test
  @DisplayName("a response body that trickles in is cut off at the read timeout")
  void aTricklingResponseIsCutOffAtTheReadTimeout() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream body = exchange.getResponseBody()) {
        for (int i = 0; i < 40; i++) {
          body.write('{');
          body.flush();
          Thread.sleep(250);
        }
      } catch (IOException | InterruptedException closedByClient) {
        // The client gave up.
      }
    });
    server.start();

    DeliveryOutcome outcome = assertTimeoutPreemptively(
      Duration.ofSeconds(5),
      () -> clientFor("/analyzer", 1, 1024 * 1024).deliver("{}")
    );

    assertFalse(outcome.reachedOpenElis());
  }

  @Test
  @DisplayName("an ordinary answer is still read in full")
  void anOrdinaryAnswerIsReadInFull() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] answer = "{\"receiptId\":\"rcpt-1\"}".getBytes();
      exchange.sendResponseHeaders(200, answer.length);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write(answer);
      }
    });
    server.start();

    DeliveryOutcome outcome = clientFor("/analyzer", 5, 1024 * 1024).deliver("{}");

    assertEquals(200, outcome.httpStatus());
    assertEquals("{\"receiptId\":\"rcpt-1\"}", outcome.body());
  }
}
