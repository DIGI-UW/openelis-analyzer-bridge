package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * One ASTM socket per port, shared by its holders. Replaces the 3.1.x assertion that every
 * connection owns a listener of its own, which is what left the published boot ports unbound.
 */
class ManagedAstmConnectionListenersTest {

  private final ManagedAstmConnectionListeners listeners = new ManagedAstmConnectionListeners(
    mock(MessageNormalizer.class),
    new DefaultASTMInterpreterFactory()
  );

  @AfterEach
  void stopListeners() {
    listeners.stopAll();
  }

  @Test
  void bootListenerAcceptsSessionsBeforeAnyConnectionExists() throws Exception {
    int port = availablePort();

    runAsBootTrigger(listeners.holdBootListener(port, "LIS01_A"));

    assertThat(listeners.isListening(port)).isTrue();
    assertAcceptsTcp(port);
  }

  @Test
  void connectionOnABootPortJoinsTheBootListenerAndLeavesItBoundOnRelease() throws Exception {
    int port = availablePort();
    runAsBootTrigger(listeners.holdBootListener(port, "LIS01_A"));

    listeners.start("bridge-42", "oe-42", port, "LIS01_A");

    assertThat(listeners.isRunning("bridge-42")).isTrue();
    assertPortOccupied(port);

    listeners.stop("bridge-42");

    assertThat(listeners.isRunning("bridge-42")).isFalse();
    assertThat(listeners.isListening(port)).isTrue();
    assertAcceptsTcp(port);
  }

  @Test
  void bootListenerThatWasNeverRunIsStartedByTheFirstConnection() throws Exception {
    int port = availablePort();
    listeners.holdBootListener(port, "LIS01_A");

    listeners.start("bridge-42", "oe-42", port, "LIS01_A");

    assertThat(listeners.isListening(port)).isTrue();
    listeners.stop("bridge-42");
    assertThat(listeners.isListening(port)).isTrue();
  }

  @Test
  void connectionOnAnUnsharedPortGetsItsOwnListenerClosedByItsLastRelease() throws Exception {
    int port = availablePort();

    listeners.start("bridge-42", "oe-42", port, "LIS01_A");

    assertThat(listeners.isRunning("bridge-42")).isTrue();
    assertPortOccupied(port);

    listeners.stop("bridge-42");

    assertThat(listeners.isListening(port)).isFalse();
    try (ServerSocket rebound = new ServerSocket(port)) {
      assertThat(rebound.isBound()).isTrue();
    }
  }

  @Test
  void reactivatingTheSameConnectionKeepsItsListener() throws Exception {
    int port = availablePort();

    listeners.start("bridge-42", "oe-42", port, "LIS01_A");
    listeners.start("bridge-42", "oe-42", port, "LIS01_A");

    assertThat(listeners.isRunning("bridge-42")).isTrue();
  }

  @Test
  void movingAConnectionToAnotherPortReleasesTheFirst() throws Exception {
    int first = availablePort();
    int second = availablePort();

    listeners.start("bridge-42", "oe-42", first, "LIS01_A");
    listeners.start("bridge-42", "oe-42", second, "LIS01_A");

    assertThat(listeners.isListening(first)).isFalse();
    assertThat(listeners.isListening(second)).isTrue();
  }

  @Test
  void twoConnectionsOnOnePortShareOneSocketUntilTheLastReleases() throws Exception {
    int port = availablePort();

    listeners.start("bridge-42", "oe-42", port, "LIS01_A");
    listeners.start("bridge-43", "oe-43", port, "LIS01_A");

    assertThat(listeners.isRunning("bridge-42")).isTrue();
    assertThat(listeners.isRunning("bridge-43")).isTrue();
    assertPortOccupied(port);

    listeners.stop("bridge-42");
    assertThat(listeners.isListening(port)).isTrue();
    assertAcceptsTcp(port);

    listeners.stop("bridge-43");
    assertThat(listeners.isListening(port)).isFalse();
  }

  @Test
  void refusesAConnectionThatDeclaresAnotherLowerLayerOnTheSamePort() throws Exception {
    int port = availablePort();
    runAsBootTrigger(listeners.holdBootListener(port, "LIS01_A"));

    assertThatThrownBy(() -> listeners.start("bridge-42", "oe-42", port, "E1381_95"))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("already listens for LIS01_A");
    assertThat(listeners.isRunning("bridge-42")).isFalse();
  }

  @Test
  void rejectsActivationWhenTheSavedPortCannotBeBound() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      assertThatThrownBy(() ->
        listeners.start(
          "bridge-42",
          "oe-42",
          occupied.getLocalPort(),
          "LIS01_A"
        )
      )
        .isInstanceOf(AnalyzerConnectionException.class)
        .hasMessageContaining("Cannot activate ASTM listener");
      assertThat(listeners.isRunning("bridge-42")).isFalse();
      assertThat(listeners.isListening(occupied.getLocalPort())).isFalse();
    }
  }

  /** What ASTMServerRunnerTrigger does at boot: run the servlet on its own thread. */
  private static void runAsBootTrigger(ASTMServlet servlet) {
    Thread.ofPlatform().start(servlet::listen);
    servlet.awaitStarted(Duration.ofSeconds(5));
  }

  private static void assertAcceptsTcp(int port) throws IOException {
    try (Socket client = new Socket()) {
      client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
      assertThat(client.isConnected()).isTrue();
    }
  }

  private static void assertPortOccupied(int port) {
    assertThatThrownBy(() -> new ServerSocket(port).close()).isInstanceOf(IOException.class);
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
