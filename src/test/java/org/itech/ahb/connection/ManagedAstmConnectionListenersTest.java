package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.ServerSocket;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
  void startsAndStopsTheListenerOwnedByOneConnection() throws Exception {
    int port = availablePort();

    listeners.start("bridge-42", "connection:bridge-42", "oe-42", port, "LIS01_A");

    assertThat(listeners.isRunning("bridge-42")).isTrue();
    assertThatThrownBy(() -> new ServerSocket(port)).isInstanceOf(java.io.IOException.class);

    listeners.stop("bridge-42");

    assertThat(listeners.isRunning("bridge-42")).isFalse();
    try (ServerSocket rebound = new ServerSocket(port)) {
      assertThat(rebound.isBound()).isTrue();
    }
  }

  @Test
  void rejectsActivationWhenTheSavedPortCannotBeBound() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      assertThatThrownBy(() ->
        listeners.start(
          "bridge-42",
          "connection:bridge-42",
          "oe-42",
          occupied.getLocalPort(),
          "LIS01_A"
        )
      )
        .isInstanceOf(AnalyzerConnectionException.class)
        .hasMessageContaining("Cannot activate ASTM listener");
      assertThat(listeners.isRunning("bridge-42")).isFalse();
    }
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
