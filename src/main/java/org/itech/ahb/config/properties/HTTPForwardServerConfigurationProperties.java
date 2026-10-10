package org.itech.ahb.config.properties;

import java.net.URI;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the HTTP server that this application should forward to when it receives
 * an ASTM message over an ASTM transmission protocol. This is used to forward the ASTM messages to a server like an LIS that only understands HTTP.
 */
@ConfigurationProperties(prefix = "org.itech.ahb.forward-http-server")
@Data
public class HTTPForwardServerConfigurationProperties {

  /**
   * The OpenELIS base URL, for example {@code https://openelis.example:8443/OpenELIS-Global}. Results
   * go to {@code {uri}/analyzer/fhir} and the health check reads {@code {uri}/health}.
   */
  private URI uri = URI.create("https://localhost:8443");

  /**
   * The username for authentication.
   */
  private String username;

  /**
   * The password for authentication.
   */
  private char[] password;

  /**
   * Disable TLS certificate and hostname verification for HTTPS connections.
   * <p>
   * Applies to both HTTPS forwarding requests and health check requests.
   * Development-only option for self-signed local environments.
   * </p>
   */
  private boolean insecureTls = false;

  /**
   * HTTP connection timeout in seconds.
   */
  private int connectTimeoutSeconds = 30;

  /**
   * HTTP read timeout in seconds.
   */
  private int readTimeoutSeconds = 30;

  /**
   * Largest response body read from OpenELIS. Its answer is a short receipt; a larger body fails
   * the attempt rather than being held in memory.
   */
  private int maxResponseBytes = 1024 * 1024;

  /**
   * Maximum number of retry attempts for outbound requests.
   */
  private int maxAttempts = 3;

  /**
   * Initial exponential backoff delay in milliseconds.
   */
  private long backoffMs = 1000;

  /** An OpenELIS endpoint under the base URL, for example {@code /analyzer/fhir}. */
  public URI resolve(String path) {
    String base = uri.toString();
    return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
  }
}
