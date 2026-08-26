package org.itech.ahb.lib.astm.handling;

import org.itech.ahb.lib.astm.concept.ASTMMessage;

/**
 * This interface defines methods for handling ASTM messages. ex. forwarding from ASTM transmission protocol to HTTP
 */
public interface ASTMHandler {
  /**
   * Gets the name of the handler. Useful for logging purposes.
   *
   * @return the name of the handler.
   */
  String getName();

  /**
   * Handles the given ASTM message with source analyzer IP information.
   *
   * @param message the ASTM message.
   * @param sourceIp the IP address of the analyzer that sent the message (may be null).
   * @return the ASTM handler response.
   */
  ASTMHandlerResponse handle(ASTMMessage message, String sourceIp);

  /**
   * Checks if the handler matches the given ASTM message. ie. should this handler be called for this message.
   *
   * @param message the ASTM message.
   * @return true if the handler matches the message, false otherwise.
   */
  boolean matches(ASTMMessage message);
}
