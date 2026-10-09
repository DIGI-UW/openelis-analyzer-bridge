package org.itech.ahb.health;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.util.HttpClientFactory;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Health indicator for the connection between this server and the HTTP server that ASTM messages should be forwarded to.
 * Useful for manually checking the health of the application/connections and for automatic monitoring.
 * Enabled/disabled via configuration property.
 *
 * management:
 *   health:
 *     httpforward:
 *       enabled: true
 */
@Component("httpforward")
@ConditionalOnEnabledHealthIndicator("httpforward")
@Slf4j
public class HTTPForwardServerHealthIndicator implements HealthIndicator {

  private final HTTPForwardServerConfigurationProperties properties;
  private final int connectTimeoutSeconds;
  private final int readTimeoutSeconds;
  private final HttpClient httpClient;
  private final org.itech.ahb.pairing.OpenElisClients openElis;
  private HttpClient pairedClient;
  private javax.net.ssl.SSLContext pairedContext;

  public HTTPForwardServerHealthIndicator(HTTPForwardServerConfigurationProperties properties) {
    this(properties, null);
  }

  /**
   * Constructor for HTTPForwardServerHealthIndicator.
   *
   * @param properties the HTTP forward server configuration properties
   * @param openElis TLS for the paired OpenELIS, used once the Bridge is paired
   */
  @org.springframework.beans.factory.annotation.Autowired
  public HTTPForwardServerHealthIndicator(
    HTTPForwardServerConfigurationProperties properties,
    org.itech.ahb.pairing.OpenElisClients openElis
  ) {
    this.openElis = openElis;
    this.properties = properties;
    this.connectTimeoutSeconds = properties.getConnectTimeoutSeconds();
    this.readTimeoutSeconds = properties.getReadTimeoutSeconds();
    this.httpClient = HttpClientFactory.create(connectTimeoutSeconds, properties.isInsecureTls(), "healthcheck");
  }

  /**
   * Checks the health of the HTTP forward server.
   *
   * @return the health status
   */
  @Override
  public Health health() {
    var refusal = properties.refusal();
    if (refusal.isPresent()) {
      return Health.down()
        .withDetail("reason", "forwarding_url_refused")
        .withDetail("message", refusal.get())
        .build();
    }
    URI healthUri = properties.healthCheckUri();
    Builder requestBuilder = HttpRequest.newBuilder()
      .method(properties.getHealthMethod().toString(), HttpRequest.BodyPublishers.ofString(properties.getHealthBody()))
      .uri(healthUri)
      .timeout(Duration.ofSeconds(readTimeoutSeconds));
    HttpClient client = pairedClient();
    if (client == null && !(properties.getUsername() == null || properties.getUsername().equals(""))) {
      log.debug(
        "using username '" +
        properties.getUsername() +
        "' to test forward http server at " +
        healthUri
      );
      addBasicAuth(requestBuilder, properties.getUsername(), properties.getPassword());
    }
    HttpRequest request = requestBuilder.build();

    try {
      log.debug("testing forward http server at " + healthUri);
      HttpResponse<String> response = (client == null ? httpClient : client).send(
        request,
        HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() == 200) {
        log.debug("testing forward http server at " + healthUri + " success");
        return Health.up().build();
      }
    } catch (IOException | InterruptedException e) {
      log.debug("error occurred communicating with http server at " + healthUri, e);
    }
    log.debug("testing forward http server at " + healthUri + " failure");
    return Health.down().build();
  }

  private synchronized HttpClient pairedClient() {
    javax.net.ssl.SSLContext context = openElis == null ? null : openElis.sslContext().orElse(null);
    if (context == null) {
      return null;
    }
    if (context != pairedContext) {
      pairedClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .sslContext(context)
        .build();
      pairedContext = context;
    }
    return pairedClient;
  }

  private void addBasicAuth(Builder requestBuilder, String username, char[] password) {
    if (password == null || password.length == 0) {
      log.warn("Password is null or empty, skipping Basic auth");
      return;
    }

    byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
    byte[] colonBytes = ":".getBytes(StandardCharsets.UTF_8);

    byte[] passwordBytes;
    try {
      CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
      ByteBuffer byteBuffer = encoder.encode(CharBuffer.wrap(password));
      passwordBytes = new byte[byteBuffer.remaining()];
      byteBuffer.get(passwordBytes);
    } catch (Exception e) {
      log.error("Failed to encode password bytes", e);
      return;
    }

    byte[] authBytes = new byte[usernameBytes.length + colonBytes.length + passwordBytes.length];
    System.arraycopy(usernameBytes, 0, authBytes, 0, usernameBytes.length);
    System.arraycopy(colonBytes, 0, authBytes, usernameBytes.length, colonBytes.length);
    System.arraycopy(passwordBytes, 0, authBytes, usernameBytes.length + colonBytes.length,
      passwordBytes.length);

    String encodedAuth = Base64.getEncoder().encodeToString(authBytes);
    requestBuilder.header("Authorization", "Basic " + encodedAuth);

    Arrays.fill(passwordBytes, (byte) 0);
    Arrays.fill(authBytes, (byte) 0);
  }
}
