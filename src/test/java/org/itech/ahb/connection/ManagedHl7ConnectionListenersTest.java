package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.routing.MessageRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** One MLLP socket per port, shared by every HL7 connection that declares it. */
class ManagedHl7ConnectionListenersTest {

  private ManagedHl7ConnectionListeners listeners;

  @AfterEach
  void stopListeners() {
    if (listeners != null) listeners.stopAll();
  }

  @Test
  void bootListenerAcceptsSessionsBeforeAnyConnectionExists() throws Exception {
    int port = availablePort();
    listeners = listeners(true, port);

    listeners.startBootListener();

    assertThat(listeners.isListening(port)).isTrue();
    assertAcceptsTcp(port);
  }

  @Test
  void disabledRuntimeBindsNothingAtBoot() throws Exception {
    int port = availablePort();
    listeners = listeners(false, port);

    listeners.startBootListener();

    assertThat(listeners.isListening(port)).isFalse();
    try (ServerSocket free = new ServerSocket(port)) {
      assertThat(free.isBound()).isTrue();
    }
  }

  @Test
  void anOccupiedBootPortDoesNotStopTheBridge() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      listeners = listeners(true, occupied.getLocalPort());

      listeners.startBootListener();

      assertThat(listeners.isListening(occupied.getLocalPort())).isFalse();
    }
  }

  @Test
  void connectionsOnTheBootPortJoinItAndLeaveItBoundOnRelease() throws Exception {
    int port = availablePort();
    listeners = listeners(true, port);
    listeners.startBootListener();

    listeners.start("hl7-a", port);
    listeners.start("hl7-b", port);

    assertThat(listeners.runningConnections()).containsEntry("hl7-a", true).containsEntry("hl7-b", true);
    listeners.stop("hl7-a");
    listeners.stop("hl7-b");
    assertThat(listeners.runningConnections()).isEmpty();
    assertThat(listeners.isListening(port)).isTrue();
    assertAcceptsTcp(port);
  }

  @Test
  void anUnsharedPortIsClosedByItsLastRelease() throws Exception {
    listeners = listeners(true, availablePort());
    int port = availablePort();

    listeners.start("hl7-a", port);
    listeners.start("hl7-b", port);
    listeners.stop("hl7-a");
    assertThat(listeners.isListening(port)).isTrue();

    listeners.stop("hl7-b");
    assertThat(listeners.isListening(port)).isFalse();
    try (ServerSocket rebound = new ServerSocket(port)) {
      assertThat(rebound.isBound()).isTrue();
    }
  }

  @Test
  void aForeignProcessOnThePortStillFailsActivation() throws Exception {
    listeners = listeners(true, availablePort());
    try (ServerSocket occupied = new ServerSocket(0)) {
      assertThatThrownBy(() -> listeners.start("hl7-a", occupied.getLocalPort()))
        .isInstanceOf(AnalyzerConnectionException.class)
        .hasMessageContaining("Cannot activate HL7 listener");
      assertThat(listeners.runningConnections()).isEmpty();
    }
  }

  private static ManagedHl7ConnectionListeners listeners(boolean enabled, int bootPort) {
    MLLPConfig config = new MLLPConfig();
    config.setEnabled(enabled);
    config.setPort(bootPort);
    return new ManagedHl7ConnectionListeners(config, mock(MessageRouter.class));
  }

  private static void assertAcceptsTcp(int port) throws IOException {
    try (Socket client = new Socket()) {
      client.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
      assertThat(client.isConnected()).isTrue();
    }
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
