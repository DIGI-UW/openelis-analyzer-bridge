package org.itech.ahb.outbox;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.util.HttpClientFactory;
import org.springframework.stereotype.Component;

/**
 * Sends one normalized bundle to OpenELIS. One attempt per call: retry scheduling belongs to the
 * outbox dispatcher, which can survive a restart, rather than to a loop inside a request thread.
 */
@Component
@Slf4j
public class FhirDeliveryClient {

  private final HTTPForwardServerConfigurationProperties httpConfig;
  private final HttpClient httpClient;
  private final int readTimeoutSeconds;

  public FhirDeliveryClient(HTTPForwardServerConfigurationProperties httpConfig) {
    this.httpConfig = httpConfig;
    this.readTimeoutSeconds = httpConfig.getReadTimeoutSeconds();
    this.httpClient = HttpClientFactory.create(
      httpConfig.getConnectTimeoutSeconds(),
      httpConfig.isInsecureTls(),
      "forwarding"
    );
    log.info(
      "FHIR delivery client: target={} connect={}s read={}s",
      targetUri(),
      httpConfig.getConnectTimeoutSeconds(),
      readTimeoutSeconds
    );
  }

  /** POST the bundle once and report what came back, or what stopped it. */
  public DeliveryOutcome deliver(String fhirJson) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(targetUri())
        .header("Content-Type", "application/fhir+json")
        .timeout(Duration.ofSeconds(readTimeoutSeconds))
        .POST(HttpRequest.BodyPublishers.ofString(fhirJson));
      if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
        addBasicAuth(builder, httpConfig.getUsername(), httpConfig.getPassword());
      }
      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return DeliveryOutcome.responded(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return DeliveryOutcome.failed(e);
    } catch (Exception e) {
      return DeliveryOutcome.failed(e);
    }
  }

  /** The OpenELIS analyzer ingestion endpoint, derived from the configured forwarding base. */
  public URI targetUri() {
    URI baseUri = httpConfig.getUri();
    String basePath = baseUri.getPath();
    if (basePath == null || basePath.isEmpty()) {
      basePath = "/analyzer";
    } else if (basePath.endsWith("/")) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    try {
      return new URI(
        baseUri.getScheme(),
        baseUri.getUserInfo(),
        baseUri.getHost(),
        baseUri.getPort(),
        basePath + "/fhir",
        baseUri.getQuery(),
        baseUri.getFragment()
      );
    } catch (URISyntaxException e) {
      log.error("Failed to build FHIR target URI", e);
      return baseUri;
    }
  }

  /**
   * Add HTTP Basic authentication.
   *
   * <p>The password is encoded from its char[] directly to bytes, never through a String, so it is
   * not interned in the String pool where it could not be cleared.
   */
  private void addBasicAuth(HttpRequest.Builder builder, String username, char[] password) {
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
    System.arraycopy(passwordBytes, 0, authBytes, usernameBytes.length + colonBytes.length, passwordBytes.length);
    builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(authBytes));
    Arrays.fill(passwordBytes, (byte) 0);
    Arrays.fill(authBytes, (byte) 0);
  }
}
