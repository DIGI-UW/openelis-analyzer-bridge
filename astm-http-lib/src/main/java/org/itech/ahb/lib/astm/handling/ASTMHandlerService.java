package org.itech.ahb.lib.astm.handling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.common.handling.HandleStatus;

/**
 * This class provides a service layer for deciding which ASTM handlers should be called for a message and then calling them.
 * Instead of directly calling a handler, this class should be used.
 */
@Slf4j
public class ASTMHandlerService {

  /**
   *  The mode of the handler service. ex, Call all handlers or just the first one.
   */
  public enum Mode {
    /**
     *  Call all handlers that match the message.
     */
    ALL,
    /**
     *  Call the first handler that matches the message.
     */
    FIRST
  }

  private List<ASTMHandler> handlers;
  private Mode mode;

  /**
   * Create a new ASTMHandlerService.
   * @param handlers the list of handlers to this handler service should be aware of.
   * @param mode the mode this handler service should operate.
   */
  public ASTMHandlerService(List<ASTMHandler> handlers, Mode mode) {
    this.handlers = handlers;
    this.mode = mode;
  }

  /**
   * Calls the relevant handler(s) for the given ASTM message without source IP information.
   *
   * @param message the ASTM message.
   * @return the ASTM handler service response.
   */
  public ASTMHandlerServiceResponse handle(ASTMMessage message) {
    return handle(message, (String) null);
  }

  /**
   * Calls the relevant handler(s) for the given ASTM message with source IP information.
   *
   * @param message the ASTM message.
   * @param sourceIp the IP address of the analyzer that sent the message (may be null).
   * @return the ASTM handler service response.
   */
  public ASTMHandlerServiceResponse handle(ASTMMessage message, String sourceIp) {
    return handle(message, sourceIp, Set.of());
  }

  /**
   * Handles the given ASTM message with the provided handler information (backward compatibility).
   *
   * @param message the ASTM message.
   * @param handlersInfos the set of handler information.
   * @return the ASTM handler service response.
   */
  public ASTMHandlerServiceResponse handle(ASTMMessage message, Set<ASTMForwardingHandlerInfo> handlersInfos) {
    return handle(message, null, handlersInfos);
  }

  /**
   * Handles the given ASTM message with the provided source IP and handler information.
   *
   * @param message the ASTM message.
   * @param sourceIp the IP address of the analyzer that sent the message (may be null).
   * @param handlersInfos the set of handler information.
   * @return the ASTM handler service response.
   */
  public ASTMHandlerServiceResponse handle(ASTMMessage message, String sourceIp, Set<ASTMForwardingHandlerInfo> handlersInfos) {
    Map<ASTMMessage, List<ASTMHandler>> messageHandlersMap = new HashMap<>();
    log.debug("finding a handler for astm message: " + message.hashCode());
    log.trace("message: '" + message.getMessage() + "'");
    for (ASTMHandler handler : handlers) {
      if (handler.matches(message)) {
        log.debug("handler: '" + handler.getName() + "' found for astm message: " + message.hashCode());
        List<ASTMHandler> matchingMessageHandlers = messageHandlersMap.getOrDefault(message, new ArrayList<>());
        matchingMessageHandlers.add(handler);
        messageHandlersMap.put(message, matchingMessageHandlers);
        if (mode == Mode.FIRST) {
          log.debug("handler service mode is " + Mode.FIRST + ", proceeding with a single handler");
          break;
        }
      }
    }
    if (!messageHandlersMap.containsKey(message)) {
      log.warn("astm message received but no handler was configured to handle the message");
      return new ASTMHandlerServiceResponse();
    }

    List<ASTMHandlerResponse> handleResponses = new ArrayList<>();
    log.debug("handling astm message...");
    for (Entry<ASTMMessage, List<ASTMHandler>> matchingMessageHandlers : messageHandlersMap.entrySet()) {
      for (ASTMHandler messageHandler : matchingMessageHandlers.getValue()) {
        try {
          ASTMHandlerResponse handleResponse = messageHandler.handle(matchingMessageHandlers.getKey(), sourceIp);
          log.debug("'" + messageHandler.getName() + "' finished handling http astm message");
          handleResponses.add(handleResponse);
        } catch (RuntimeException e) {
          log.error(
            "unexpected error occurred during '" +
            messageHandler.getName() +
            "' handling astm message: " +
            matchingMessageHandlers.getKey(),
            e
          );
          handleResponses.add(new ASTMHandlerResponse("", HandleStatus.GENERIC_FAIL, false, messageHandler));
          // TODO add some handle exception handling. retry queue? db save?
          // handler.handleError();
        }
      }
    }
    return new ASTMHandlerServiceResponse(handleResponses);
  }
}
