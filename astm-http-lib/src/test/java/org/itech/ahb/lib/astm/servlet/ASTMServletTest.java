package org.itech.ahb.lib.astm.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.junit.jupiter.api.Test;

class ASTMServletTest {

  @Test
  void acknowledgesOnlyAfterItsListenerIsBound() throws Exception {
    int port = availablePort();
    ASTMServlet servlet = servlet(port);
    Thread thread = Thread.ofPlatform().start(servlet::listen);

    servlet.awaitStarted(Duration.ofSeconds(2));

    assertTrue(servlet.isRunning());
    servlet.stop();
    thread.join(Duration.ofSeconds(2));
    assertFalse(thread.isAlive());
  }

  @Test
  void reportsTheBindFailureInsteadOfAppearingActive() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      ASTMServlet servlet = servlet(occupied.getLocalPort());
      Thread thread = Thread.ofPlatform().start(servlet::listen);

      assertThrows(IllegalStateException.class, () -> servlet.awaitStarted(Duration.ofSeconds(2)));

      thread.join(Duration.ofSeconds(2));
      assertFalse(servlet.isRunning());
    }
  }

  private static ASTMServlet servlet(int port) {
    return new ASTMServlet(
      new ASTMHandlerService(List.of(), Mode.FIRST),
      new DefaultASTMInterpreterFactory(),
      port,
      ASTMVersion.LIS01_A
    );
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
