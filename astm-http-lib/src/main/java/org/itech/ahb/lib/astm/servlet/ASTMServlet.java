package org.itech.ahb.lib.astm.servlet;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.communication.GeneralASTMCommunicator;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMReceiveThread;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;

/**
 * This class represents a servlet that listens for ASTM messages via an ASTM transmission protocol
 * on a specified port and handles them using the provided handler service.
 */
@Slf4j
public class ASTMServlet {

  /**
   * Enum representing the ASTM version.
   */
  public enum ASTMVersion {
    LIS01_A,
    E1381_95,
    /**
     * Non-compliant ASTM version. A non-standard version of the ASTM transmission protocol
     * where the message is communicaed character by character without the normal ASTM frames,
     * control characters, checksums, etc.
     */
    NON_COMPLIANT
  }

  private final ASTMHandlerService astmHandlerService;
  private final ASTMInterpreterFactory astmInterpreterFactory;
  private final int listenPort;
  private final ASTMVersion astmVersion;
  private final AtomicBoolean starting = new AtomicBoolean(false);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CountDownLatch startupAttempted = new CountDownLatch(1);
  private volatile ServerSocket serverSocket;
  private volatile Throwable startupFailure;

  /**
   * Constructs a new ASTMServlet with the specified handler service, interpreter factory, listen port, and ASTM version.
   *
   * @param astmHandlerService the handler service to use for processing messages.
   * @param astmInterpreterFactory the interpreter factory to use for interpreting messages.
   * @param listenPort the port to listen on for ASTM messages.
   * @param astmVersion the ASTM version to use for communication.
   */
  public ASTMServlet(
    ASTMHandlerService astmHandlerService,
    ASTMInterpreterFactory astmInterpreterFactory,
    int listenPort,
    ASTMVersion astmVersion
  ) {
    this.astmHandlerService = astmHandlerService;
    this.astmInterpreterFactory = astmInterpreterFactory;
    this.listenPort = listenPort;
    this.astmVersion = astmVersion;
  }

  /**
   * Starts the servlet to listen for ASTM messages on the specified port.
   *
   * Will spawn a new thread for every incoming connection.
   */
  public void listen() {
    if (!starting.compareAndSet(false, true)) {
      log.warn("Server is already running on port " + listenPort);
      return;
    }

    try {
      serverSocket = new ServerSocket(listenPort);
      running.set(true);
      startupAttempted.countDown();
      log.info(
        "Server is listening on port " + listenPort + " for ASTM transmission protocol: " + astmVersion + " messages"
      );
      // Communication Endpoint for the client and server.
      while (running.get()) {
        try {
          // Waiting for socket connection
          Socket s = serverSocket.accept();
          s.setTcpNoDelay(true); // Disable Nagle — ASTM requires immediate ACK/NAK per frame
          new ASTMReceiveThread(
            new GeneralASTMCommunicator(astmInterpreterFactory, s, astmVersion),
            s,
            astmHandlerService
          ).start();
        } catch (IOException e) {
          if (running.get()) {
            log.error("Error accepting connection on port " + listenPort, e);
          }
          // If not running, this is expected due to socket close during shutdown
        }
      }
    } catch (Exception e) {
      startupFailure = e;
      startupAttempted.countDown();
      log.error("an exception caused the astm server to shut down", e);
    } finally {
      running.set(false);
      starting.set(false);
      startupAttempted.countDown();
      closeServerSocket();
    }
  }

  /** Waits until the socket is bound or startup has failed. */
  public void awaitStarted(Duration timeout) {
    boolean completed;
    try {
      completed = startupAttempted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while starting ASTM listener on port " + listenPort, exception);
    }
    if (!completed) {
      throw new IllegalStateException("Timed out starting ASTM listener on port " + listenPort);
    }
    if (startupFailure != null) {
      throw new IllegalStateException("Cannot start ASTM listener on port " + listenPort, startupFailure);
    }
    if (!running.get()) {
      throw new IllegalStateException("ASTM listener stopped while starting on port " + listenPort);
    }
  }

  /**
   * Stops the servlet from listening for ASTM messages.
   * This will close the server socket and cause listen() to exit.
   */
  public void stop() {
    log.info("Stopping ASTM server on port " + listenPort);
    running.set(false);
    closeServerSocket();
  }

  /**
   * Checks if the servlet is currently running.
   *
   * @return true if the servlet is running, false otherwise.
   */
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Gets the port this servlet is listening on.
   *
   * @return the listen port
   */
  public int getListenPort() {
    return listenPort;
  }

  private void closeServerSocket() {
    if (serverSocket != null && !serverSocket.isClosed()) {
      try {
        serverSocket.close();
        log.debug("Closed server socket on port " + listenPort);
      } catch (IOException e) {
        log.error("Error closing server socket on port " + listenPort, e);
      }
    }
  }
}
