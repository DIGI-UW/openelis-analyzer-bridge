package org.itech.ahb.config.properties;

import java.net.URI;
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

  /**
   * The URI of the HTTP forward server.
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
   * The URI for health checks to ensure the connection between the http server
   * and this server is healthy.
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
}
