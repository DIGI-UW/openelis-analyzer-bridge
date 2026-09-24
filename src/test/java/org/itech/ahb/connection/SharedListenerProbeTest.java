package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Checking a connection the analyzer opens, against real sockets. The Bridge's listener decides the
 * result: a port it already serves through a boot or shared listener is ready, a port held by
 * another process is in use, a free port is ready to be bound. The analyzer check is advisory: its
 * saved address is reachable or not, and without one it is skipped as not checkable.
 */
class SharedListenerProbeTest {

  /** Reserved for documentation (RFC 5737), so it never routes. */
  private static final String UNROUTABLE = "192.0.2.1";

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

    ObjectNode result = probe.execute(request(), serverConnection(port, null), astmProfile());

    assertCheck(result.path("checks").get(0), "listener", "PASSED", "listener.ready");
    assertThat(result.path("checks")).hasSize(2);
    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aPortSharedWithAnotherActiveConnectionIsReady() throws Exception {
    int port = availablePort();
    astmListeners.start("gx-lab-a", "oe-gx-lab-a", port, "LIS01_A");

    ObjectNode result = probe.execute(request(), serverConnection(port, null), astmProfile());

    assertCheck(result.path("checks").get(0), "listener", "PASSED", "listener.ready");
    assertThat(result.path("checks")).hasSize(2);
    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aPortHeldByAnotherProcessIsStillReportedInUse() throws Exception {
    try (ServerSocket foreign = new ServerSocket(0)) {
      ObjectNode result = probe.execute(request(), serverConnection(foreign.getLocalPort(), null), astmProfile());

      assertThat(result.path("status").asText()).isEqualTo("FAILED");
      assertCheck(result.path("checks").get(0), "listener", "FAILED", "listener.port.in.use");
    }
  }

  @Test
  void aFreePortIsReadyToBind() throws Exception {
    ObjectNode result = probe.execute(request(), serverConnection(availablePort(), null), astmProfile());

    assertCheck(result.path("checks").get(0), "listener", "PASSED", "listener.ready");
    assertThat(result.path("checks")).hasSize(2);
    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void withoutAnAnalyzerAddressTheAnalyzerIsSkippedAsNotCheckable() throws Exception {
    ObjectNode result = probe.execute(request(), serverConnection(availablePort(), null), astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertCheck(result.path("checks").get(1), "analyzer", "SKIPPED", "analyzer.address.missing");
    new AnalyzerConnectionContractValidator(objectMapper).validateProbeResult(result);
  }

  /**
   * Loopback always answers, by ICMP or by refusing TCP port 7, so this proves the wiring from the
   * saved address to the check, not that a firewalled analyzer answers from a non-root container.
   */
  @Test
  void aReachableAnalyzerAddressPasses() throws Exception {
    ObjectNode result = probe.execute(request(), serverConnection(availablePort(), "127.0.0.1"), astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertCheck(result.path("checks").get(1), "analyzer", "PASSED", "analyzer.reachable");
  }

  @Test
  void anUnreachableAnalyzerAddressIsReportedWithoutFailingTheCheck() throws Exception {
    ObjectNode connection = serverConnection(availablePort(), UNROUTABLE);
    connection.withObject("values").put("connectTimeoutMillis", 500);

    ObjectNode result = probe.execute(request(), connection, astmProfile());

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertCheck(result.path("checks").get(0), "listener", "PASSED", "listener.ready");
    assertCheck(result.path("checks").get(1), "analyzer", "FAILED", "analyzer.unreachable");
    assertThat(result.path("checks").get(1).path("details").path("host").asText()).isEqualTo(UNROUTABLE);
    new AnalyzerConnectionContractValidator(objectMapper).validateProbeResult(result);
  }

  private static void assertCheck(JsonNode check, String key, String status, String messageKey) {
    assertThat(check.path("key").asText()).isEqualTo(key);
    assertThat(check.path("status").asText()).isEqualTo(status);
    assertThat(check.path("messageKey").asText()).isEqualTo(messageKey);
  }

  private ObjectNode request() {
    return objectMapper.createObjectNode().put("requestId", "probe-1");
  }

  private ObjectNode astmProfile() {
    ObjectNode profile = objectMapper.createObjectNode();
    profile.putObject("protocol").put("name", "ASTM");
    return profile;
  }

  private ObjectNode serverConnection(int port, String host) {
    ObjectNode connection = objectMapper.createObjectNode();
    connection.put("connectionId", "gx-lab-b");
    ObjectNode profileRef = connection.putObject("profileRef");
    profileRef.put("profileId", "genexpert-astm");
    profileRef.put("revision", 5);
    profileRef.put("fingerprint", "sha256:" + "1".repeat(64));
    connection.put("configRevision", 1);
    connection.put("configFingerprint", "sha256:" + "3".repeat(64));
    ObjectNode values = connection.putObject("values");
    values.put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", port);
    if (host != null) {
      values.put("host", host);
    }
    return connection;
  }

  private static int availablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
