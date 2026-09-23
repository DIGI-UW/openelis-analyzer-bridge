package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import org.itech.ahb.connectivity.DefaultConnectionProbeExecutor;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checking a SERVER connection that is not active yet, against real sockets: a port the Bridge
 * already serves through a boot or shared listener is ready, a port held by another process is in
 * use, and a free port is ready to be bound.
 */
class SharedListenerProbeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ManagedAstmConnectionListeners astmListeners = new ManagedAstmConnectionListeners(
    mock(MessageNormalizer.class),
    new DefaultASTMInterpreterFactory()
  );
  private final AnalyzerConnectionProbe probe = new AnalyzerConnectionProbe(
    objectMapper,
    Clock.systemUTC(),
    new DefaultConnectionProbeExecutor(),
    (protocol, port) -> "ASTM".equals(protocol) && astmListeners.isListening(port)
  );

  @AfterEach
  void stopListeners() {
    astmListeners.stopAll();
  }

  @Test
  void aPortServedByTheBridgesBootListenerIsReady() throws Exception {
    int port = availablePort();
    ASTMServlet boot = astmListeners.holdBootListener(port, "LIS01_A");
    Thread.ofPlatform().start(boot::listen);
    boot.awaitStarted(Duration.ofSeconds(5));

    ObjectNode result = probe.execute(request(), inactiveServerConnection(port), astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.ready");
  }

  @Test
  void aPortSharedWithAnotherActiveConnectionIsReady() throws Exception {
    int port = availablePort();
    astmListeners.start("gx-lab-a", "oe-gx-lab-a", port, "LIS01_A");

    ObjectNode result = probe.execute(request(), inactiveServerConnection(port), astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.ready");
  }

  @Test
  void aPortHeldByAnotherProcessIsStillReportedInUse() throws Exception {
    try (ServerSocket foreign = new ServerSocket(0)) {
      ObjectNode result = probe.execute(request(), inactiveServerConnection(foreign.getLocalPort()), astmProfile());

      assertThat(result.path("status").asText()).isEqualTo("FAILED");
      assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.port.in.use");
    }
  }

  @Test
  void aFreePortIsReadyToBind() throws Exception {
    ObjectNode result = probe.execute(request(), inactiveServerConnection(availablePort()), astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.ready");
  }

  private ObjectNode request() {
    return objectMapper.createObjectNode().put("requestId", "probe-1");
  }

  private ObjectNode astmProfile() {
    ObjectNode profile = objectMapper.createObjectNode();
    profile.putObject("protocol").put("name", "ASTM");
    return profile;
  }

  private ObjectNode inactiveServerConnection(int port) {
    ObjectNode connection = objectMapper.createObjectNode();
    connection.put("connectionId", "gx-lab-b");
    ObjectNode profileRef = connection.putObject("profileRef");
    profileRef.put("profileId", "genexpert-astm");
    profileRef.put("revision", 5);
    profileRef.put("fingerprint", "sha256:" + "1".repeat(64));
    connection.put("configRevision", 1);
    connection.put("configFingerprint", "sha256:" + "3".repeat(64));
    connection.putObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", port);
    return connection;
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
