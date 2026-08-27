package org.itech.ahb.lib.astm.handling;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.communication.Communicator;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.exception.ASTMCommunicationException;
import org.itech.ahb.lib.astm.exception.FrameParsingException;
import org.itech.ahb.lib.common.handling.HandleStatus;

/**
 * This class represents a thread that controls the flow of receiving ASTM messages via a communicator
 * and then calling the handler service to process the message.
 */
@Slf4j
public class ASTMReceiveThread extends Thread {

  private final Socket socket;
  private final Communicator communicator;
  private ASTMHandlerService astmHandlerService;
  private boolean lineWasContentious;

  /**
   * Constructs a new ASTMReceiveThread with the specified communicator, socket, and handler service.
   *
   * @param communicator the communicator to use for receiving messages.
   * @param socket the socket to use for communication.
   * @param astmHandlerService the handler service to use for processing messages.
   */
  public ASTMReceiveThread(Communicator communicator, Socket socket, ASTMHandlerService astmHandlerService) {
    this.communicator = communicator;
    this.socket = socket;
    this.astmHandlerService = astmHandlerService;
  }

  /**
   * Constructs a new ASTMReceiveThread with the specified communicator and handler service.
   *
   * @param communicator the communicator to use for receiving messages.
   * @param astmHandlerService the handler service to use for processing messages.
   */
  public ASTMReceiveThread(Communicator communicator, ASTMHandlerService astmHandlerService) {
    this.communicator = communicator;
    this.socket = null;
    this.astmHandlerService = astmHandlerService;
  }

  /**
   * Constructs a new ASTMReceiveThread with the specified communicator, socket, handler service, and line contention flag.
   *
   * @param communicator the communicator to use for receiving messages.
   * @param socket the socket to use for communication.
   * @param astmHandlerService the handler service to use for processing messages.
   * @param lineWasContentious indicates if the line was contentious.
   */
  public ASTMReceiveThread(
    Communicator communicator,
    Socket socket,
    ASTMHandlerService astmHandlerService,
    boolean lineWasContentious
  ) {
    this.communicator = communicator;
    this.socket = socket;
    this.astmHandlerService = astmHandlerService;
    this.lineWasContentious = lineWasContentious;
  }

  /**
   * Runs the thread to receive and handle ASTM messages.
   * Extracts the source analyzer IP from the socket and passes it to the handler service.
   */
  @Override
  public void run() {
    log.trace("thread started to receive ASTM message");

    // Extract source IP from socket before processing (FR-001)
    String sourceIp = extractSourceIp(socket);

    try {
      ASTMMessage message;
      try {
        message = communicator.receiveProtocol(lineWasContentious);
      } catch (IllegalStateException | ASTMCommunicationException e) {
        log.error("an error occurred understanding what was received from the astm sender", e);
        return;
      } catch (FrameParsingException e) {
        log.error("an error occurred parsing the received frames to an ASTM message", e);
        return;
      } catch (InterruptedException e) {
        log.error("the thread was interrupted during receive protocol", e);
        Thread.currentThread().interrupt();
        return;
      } catch (SocketTimeoutException e) {
        log.error("there was a timeout in the receive protocol at the socket level, abandoning message", e);
        return;
      } catch (EOFException e) {
        log.info("the astm sender closed the connection without completing a message, abandoning message");
        return;
      }

      // Pass source IP to handler service so it can be included in HTTP headers
      ASTMHandlerServiceResponse response = astmHandlerService.handle(message, sourceIp);
      if (response.getResponses() == null || response.getResponses().size() == 0) {
        log.error("message was unhandled");
      } else {
        for (ASTMHandlerResponse handlerResponse : response.getResponses()) {
          if (handlerResponse.getStatus() != HandleStatus.SUCCESS) {
            log.error("message was not handled successfully by: " + handlerResponse.getHandler().getName());
          } else {
            log.debug("message was handled successfully by: " + handlerResponse.getHandler().getName());
          }
        }
      }
    } catch (IOException e) {
      log.error("error occurred communicating with astm sender", e);
    } finally {
      if (socket != null && !socket.isClosed()) {
        try {
          socket.close();
          log.debug("successfully closed socket with astm sender");
        } catch (IOException e) {
          log.error("error occurred closing socket with astm sender", e);
        }
      }
    }
  }

  /**
   * Extracts the source IP address from the socket's remote address.
   * This method handles both IPv4 and IPv6 addresses (FR-003).
   * If extraction fails, logs a warning and returns null for graceful degradation (FR-004).
   *
   * @param socket the socket to extract the IP from
   * @return the source IP address as a string, or null if extraction fails
   */
  private String extractSourceIp(Socket socket) {
    try {
      if (socket == null || socket.isClosed()) {
        log.warn("Cannot extract source IP: socket is null or closed");
        return null;
      }
      InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
      if (address == null) {
        log.warn("Cannot extract source IP: remote address is null");
        return null;
      }
      String ip = address.getAddress().getHostAddress();
      log.debug("Extracted source IP: {}", ip);
      return ip;
    } catch (Exception e) {
      log.warn("Error extracting source IP from socket", e);
      return null;
    }
  }

  /**
   * Checks if the establishment phase of the communication succeeded.
   *
   * @return true if the establishment succeeded, false otherwise.
   */
  public boolean didReceiveEstablishmentSucceed() {
    return communicator.didReceiveEstablishmentSucceed();
  }
}
