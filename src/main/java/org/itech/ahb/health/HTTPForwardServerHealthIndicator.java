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

  /**
   * Constructor for HTTPForwardServerHealthIndicator.
   *
   * @param properties the HTTP forward server configuration properties
   */
  public HTTPForwardServerHealthIndicator(
    HTTPForwardServerConfigurationProperties properties
  ) {
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
    if (properties.getHealthUri() == null) {
      return Health.unknown().build();
    }
    log.debug("creating request to test forward http server at " + properties.getHealthUri().toString());
    Builder requestBuilder = HttpRequest.newBuilder()
      .method(properties.getHealthMethod().toString(), HttpRequest.BodyPublishers.ofString(properties.getHealthBody()))
      .uri(properties.getHealthUri())
      .timeout(Duration.ofSeconds(readTimeoutSeconds));
    if (!(properties.getUsername() == null || properties.getUsername().equals(""))) {
      log.debug(
        "using username '" +
        properties.getUsername() +
        "' to test forward http server at " +
        properties.getUri().toString()
      );
      addBasicAuth(requestBuilder, properties.getUsername(), properties.getPassword());
    }
    HttpRequest request = requestBuilder.build();

    try {
      log.debug("testing forward http server at " + properties.getHealthUri().toString());
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        log.debug("testing forward http server at " + properties.getHealthUri().toString() + " success");
        return Health.up().build();
      }
    } catch (IOException | InterruptedException e) {
      log.debug("error occurred communicating with http server at " + properties.getHealthUri().toString(), e);
    }
    log.debug("testing forward http server at " + properties.getHealthUri().toString() + " failure");
    return Health.down().build();
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
