package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.metrics.MetricsService;
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
  void aPeerOutsideTheAllowedSourcesIsClosedBeforeItSendsAnything() throws Exception {
    ManagedAstmConnectionListeners restricted = withSources("192.0.2.0/24");
    int port = availablePort();
    try {
      restricted.start("bridge-7", "oe-7", port, "LIS01_A");

      try (Socket peer = new Socket("127.0.0.1", port)) {
        peer.setSoTimeout(5_000);
        assertThat(peer.getInputStream().read()).isEqualTo(-1);
      }
    } finally {
      restricted.stopAll();
    }
  }

  @Test
  void aPeerInTheAllowedSourcesIsAnswered() throws Exception {
    ManagedAstmConnectionListeners restricted = withSources("127.0.0.0/8");
    int port = availablePort();
    try {
      restricted.start("bridge-8", "oe-8", port, "LIS01_A");

      try (Socket peer = new Socket("127.0.0.1", port)) {
        peer.setSoTimeout(5_000);
        peer.getOutputStream().write(0x05);
        peer.getOutputStream().flush();
        assertThat(peer.getInputStream().read()).as("ACK").isEqualTo(0x06);
      }
    } finally {
      restricted.stopAll();
    }
  }

  @Test
  void sessionLimitRefusalsAreCountedOnceAndAdmittedPeersAreNotCounted() throws Exception {
    int port = availablePort();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    var refused = meters.counter("bridge.listener.refused", "port", Integer.toString(port));
    ManagedAstmConnectionListeners limited = new ManagedAstmConnectionListeners(
      mock(MessageNormalizer.class),
      new DefaultASTMInterpreterFactory(),
      new ListenerSourcePolicy("", false, 1, null, new MetricsService(meters))
    );
    try {
      limited.start("bridge-limited", "oe-limited", port, "LIS01_A");
      try (Socket first = new Socket("127.0.0.1", port)) {
        first.setSoTimeout(5000);
        first.getOutputStream().write(0x05);
        first.getOutputStream().flush();
        assertThat(first.getInputStream().read()).as("admitted peer is answered").isEqualTo(0x06);
        assertThat(refused.count()).isZero();
        try (Socket second = new Socket("127.0.0.1", port)) {
          second.setSoTimeout(5000);
          assertThat(second.getInputStream().read()).isEqualTo(-1);
          assertThat(refused.count()).isEqualTo(1);
        }
      }
    } finally {
      limited.stopAll();
    }
  }

  private static ManagedAstmConnectionListeners withSources(String allowedSources) {
    return new ManagedAstmConnectionListeners(
      mock(MessageNormalizer.class),
      new DefaultASTMInterpreterFactory(),
      new ListenerSourcePolicy(allowedSources, false, null)
    );
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
      assertThatThrownBy(() -> listeners.start("bridge-42", "oe-42", occupied.getLocalPort(), "LIS01_A"))
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
