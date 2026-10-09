package org.itech.ahb.config.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Configuration properties for the HTTP server that this application should forward to when it receives
 * an ASTM message over an ASTM transmission protocol. This is used to forward the ASTM messages to a server like an LIS that only understands HTTP.
 */
@ConfigurationProperties(prefix = "org.itech.ahb.forward-http-server")
@Data
public class HTTPForwardServerConfigurationProperties {

  private static final String URI_PROPERTY = "org.itech.ahb.forward-http-server.uri";
  private static final String HEALTH_URI_PROPERTY = "org.itech.ahb.forward-http-server.health-uri";

  /**
   * The OpenELIS base URL, including its context path, for example
   * {@code https://lab.example.org/api/OpenELIS-Global}. Results go to {@code {uri}/analyzer/fhir}
   * and the health check probes {@code {uri}/health}.
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
   * No longer read: the health check probes {@code {uri}/health}. Still bound so that a deployment
   * which sets it is refused with a message, rather than silently probing somewhere else.
   */
  private URI healthUri;

  /**
   * The HTTP method for health checks.
   */
  private RequestMethod healthMethod = RequestMethod.GET;

  /**
   * The body of the health check request.
   */
  private String healthBody = "";

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

  /** Where results are delivered: the OpenELIS analyzer endpoint under the base URL. */
  public URI deliveryUri() {
    return underBase("/analyzer/fhir");
  }

  /** What the forwarding health check probes, under the same base results go to. */
  public URI healthCheckUri() {
    return underBase("/health");
  }

  /**
   * Why this configuration must not be used for delivery, or empty when it can be.
   *
   * <p>A URI ending in {@code /analyzer} was the base before 3.3.1. Accepting it now would send
   * results to {@code /analyzer/analyzer/fhir}; guessing what was meant would leave two accepted
   * forms, which is how a site came to post results to the FHIR server instead of the analyzer
   * endpoint.
   */
  public Optional<String> refusal() {
    List<String> problems = new ArrayList<>();
    String path = trimmedPath();
    if (path.equals("/analyzer") || path.endsWith("/analyzer")) {
      problems.add(
        URI_PROPERTY +
        " is " +
        uri +
        ", which ends in /analyzer. It is now the OpenELIS base URL: set it to " +
        withPath(path.substring(0, path.length() - "/analyzer".length()))
      );
    }
    if (healthUri != null) {
      problems.add(
        HEALTH_URI_PROPERTY +
        " is set; remove it. The health check now probes " +
        healthCheckUri() +
        ", under " +
        URI_PROPERTY
      );
    }
    if (problems.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
      "Results are held and not delivered until this is fixed and the Bridge restarted. " +
      String.join(". ", problems)
    );
  }

  private URI underBase(String suffix) {
    return withPath(trimmedPath() + suffix);
  }

  private String trimmedPath() {
    String path = uri.getPath() == null ? "" : uri.getPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  private URI withPath(String path) {
    try {
      return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot build a URI under " + uri, e);
    }
  }
}
