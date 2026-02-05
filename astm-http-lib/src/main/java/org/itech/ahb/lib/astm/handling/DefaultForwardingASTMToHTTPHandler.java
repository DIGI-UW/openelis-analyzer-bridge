package org.itech.ahb.lib.astm.handling;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.itech.ahb.lib.util.LogUtil;

/**
 * This class is a default handler that is at the core of this library.
 * It takes an ASTM message and forwards it over HTTP(S), optionally with a username and password
 * for BASIC authentication.
 */
@Slf4j
public class DefaultForwardingASTMToHTTPHandler implements ASTMHandler {

  /** HTTP header name for the source analyzer IP address */
  public static final String SOURCE_IP_HEADER = "X-Source-Analyzer-IP";

  private final URI forwardingUri;
  private final String username;
  private final char[] password;

  /**
   * Constructs a new DefaultForwardingASTMToHTTPHandler with a URI endpoint it will forward to.
   *
   * @param forwardingUri the endpoint for ASTM message to be forwarded to over HTTP(S).
   */
  public DefaultForwardingASTMToHTTPHandler(URI forwardingUri) {
    this.forwardingUri = forwardingUri;
    this.username = null;
    this.password = new char[0];
  }

  /**
   * Constructs a new DefaultForwardingASTMToHTTPHandler with a URI endpoint it will forward to and a username/password combo to use for authentication.
   *
   * @param forwardingUri the endpoint for ASTM message to be forwarded to over HTTP(S).
   * @param username the username.
   * @param password the password.
   */
  public DefaultForwardingASTMToHTTPHandler(URI forwardingUri, String username, char[] password) {
    this.forwardingUri = forwardingUri;
    this.username = username;
    this.password = password;
  }

  /**
   * Handles the given ASTM message by forwarding it over HTTP(S) to the URI endpoint that was passed into this class.
   * Includes the source analyzer IP in the X-Source-Analyzer-IP header when available.
   *
   * @param message the ASTM message.
   * @param sourceIp the IP address of the analyzer that sent the message (may be null).
   * @return the ASTM handler response.
   */
  @Override
  public ASTMHandlerResponse handle(ASTMMessage message, String sourceIp) {
    HttpClient client = HttpClient.newHttpClient();
    log.debug("creating request to forward to http server at " + forwardingUri.toString());
    log.trace("request: '" + message.getMessage() + "'");
    Builder requestBuilder = HttpRequest.newBuilder() //
      .uri(forwardingUri) //
      .POST(HttpRequest.BodyPublishers.ofString(message.getMessage())); //

    // Add source analyzer IP header if available (FR-002)
    if (sourceIp != null && !sourceIp.isEmpty()) {
      requestBuilder.header(SOURCE_IP_HEADER, sourceIp);
      log.debug("added {} header with value: {}", SOURCE_IP_HEADER, sourceIp);
    }

    if (!(username == null || username.equals(""))) {
      log.debug("using username '" + username + "' to forward to http server at " + forwardingUri.toString());
      requestBuilder.header(
        "Authorization",
        "Basic " + Base64.getEncoder().encodeToString((username + ":" + new String(password)).getBytes())
      );
    }
    HttpRequest request = requestBuilder.build();

    try {
      log.debug("forwarding request to http server at " + forwardingUri.toString());
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      log.debug("received " + response.statusCode() + " response from http server at " + forwardingUri.toString());
      log.trace("response: " + LogUtil.convertForDisplay(response.body()));
      if (response.statusCode() == 200) {
        return new ASTMHandlerResponse(response.body(), HandleStatus.SUCCESS, false, this);
      }
      return new ASTMHandlerResponse(response.body(), HandleStatus.FORWARD_FAIL_BAD_RESPONSE, false, this);
    } catch (IOException | InterruptedException e) {
      log.error("error occurred communicating with http server at " + forwardingUri.toString(), e);
      return new ASTMHandlerResponse("", HandleStatus.FORWARD_FAIL_ERROR, false, this);
    }
  }

  /**
   * This handler matches all DefaultASTMMessage messages.
   *
   * @param message
   * @return boolean
   */
  @Override
  public boolean matches(ASTMMessage message) {
    return message instanceof DefaultASTMMessage;
  }

  /**
   * @return the name of this handler for logging purposes
   */
  @Override
  public String getName() {
    return "Forwarding ASTM to HTTP Handler";
  }
}
