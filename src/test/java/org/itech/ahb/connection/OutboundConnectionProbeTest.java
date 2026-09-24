package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.connectivity.DefaultConnectionProbeExecutor;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real independent inbound/outbound sockets. Saved-profile publication is covered separately. */
class OutboundConnectionProbeTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final ManagedAstmConnectionListeners listeners = new ManagedAstmConnectionListeners(
    mock(MessageNormalizer.class),
    new DefaultASTMInterpreterFactory()
  );
  private final ASTMLIS1AListenServerConfigurationProperties inbound =
    new ASTMLIS1AListenServerConfigurationProperties();
  private final AnalyzerOutboundDefaults defaults = new AnalyzerOutboundDefaults();
  private ObjectNode profile;
  private ObjectNode connection;
  private AnalyzerConnectionProbe probe;

  @BeforeEach
  void startInboundListener() throws Exception {
    try (ServerSocket available = new ServerSocket(0)) {
      inbound.setPort(available.getLocalPort());
    }
    listeners.start("inbound", "oe-probe", inbound.getPort(), "LIS01_A");
    profile = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm-v5.json"));
    connection = mapper
      .createObjectNode()
      .put("connectionId", "outbound-probe")
      .put("configRevision", 1)
      .put("configFingerprint", "sha256:" + "1".repeat(64));
    connection
      .putObject("profileRef")
      .put("profileId", "genexpert-astm")
      .put("revision", 5)
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    connection
      .putObject("values")
      .put("connectionRole", "SERVER")
      .put("transport", "TCP/IP")
      .put("dataFlow", "TWO_WAY")
      .put("host", "127.0.0.1")
      .put("port", 1200)
      .put("connectTimeoutMillis", 300);
    probe = new AnalyzerConnectionProbe(
      mapper,
      Clock.systemUTC(),
      new DefaultConnectionProbeExecutor(),
      (protocol, port) -> listeners.isListening(port),
      new AnalyzerListenerPorts(inbound, new ASTME138195ListenServerConfigurationProperties(), new MLLPConfig()),
      new AnalyzerOutboundEndpoint(defaults)
    );
  }

  @AfterEach
  void stopInboundListener() {
    listeners.stopAll();
  }

  @ParameterizedTest
  @ValueSource(strings = { "Connection override", "Profile default", "Bridge default" })
  void probesTheResolvedRemoteDestinationWhileTheInboundListenerIsHealthy(String source) throws Exception {
    try (ServerSocket remote = new ServerSocket(0)) {
      defaults.setAstmPort(remote.getLocalPort());
      if ("Profile default".equals(source)) profile
        .withObject("transport_config")
        .withObject("TCP/IP")
        .put("default_port", remote.getLocalPort());
      if ("Connection override".equals(source)) connection
        .withObject("values")
        .put("outboundPort", remote.getLocalPort());
      CompletableFuture<Integer> exchange = respond(remote, 0x06);

      ObjectNode result = probe.execute(mapper.createObjectNode().put("requestId", "remote-test"), connection, profile);

      assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(result.path("checks").get(0).path("details").path("port").asInt()).isEqualTo(inbound.getPort());
      var remoteCheck = result.path("checks").get(1);
      assertThat(remoteCheck.path("key").asText()).isEqualTo("remote-protocol");
      assertThat(remoteCheck.path("details").path("port").asInt()).isEqualTo(remote.getLocalPort());
      assertThat(remoteCheck.path("details").path("portSource").asText()).isEqualTo(source);
      assertThat(exchange.get(3, TimeUnit.SECONDS)).isEqualTo(0x04);
      new AnalyzerConnectionContractValidator(mapper).validateProbeResult(result);
    }
  }

  @Test
  void wrongRemoteProtocolFailsEvenThoughTheInboundListenerIsHealthy() throws Exception {
    try (ServerSocket remote = new ServerSocket(0)) {
      defaults.setAstmPort(remote.getLocalPort());
      CompletableFuture<Integer> exchange = respond(remote, 0x15);
      ObjectNode result = probe.execute(
        mapper.createObjectNode().put("requestId", "wrong-port-test"),
        connection,
        profile
      );
      assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("PASSED");
      assertThat(result.path("status").asText()).isEqualTo("FAILED");
      var failed = result.path("checks").get(1);
      assertThat(failed.path("messageKey").asText()).isEqualTo("remote.astm.response.invalid");
      assertThat(failed.path("details").path("host").asText()).isEqualTo("127.0.0.1");
      assertThat(failed.path("details").path("port").asInt()).isEqualTo(remote.getLocalPort());
      assertThat(failed.path("details").path("remediation").asText()).contains("destination port");
      assertThat(exchange.get(3, TimeUnit.SECONDS)).isEqualTo(-1);
    }
  }

  @Test
  void refusedFallbackReportsDestinationAndExplicitOverrideCanBeRetested() throws Exception {
    int refusedPort;
    try (ServerSocket available = new ServerSocket(0)) {
      refusedPort = available.getLocalPort();
    }
    defaults.setAstmPort(refusedPort);
    ObjectNode failed = probe.execute(mapper.createObjectNode().put("requestId", "refused"), connection, profile);
    assertThat(failed.path("status").asText()).isEqualTo("FAILED");
    assertThat(failed.path("checks").get(1).path("messageKey").asText()).isEqualTo("remote.refused");
    assertThat(failed.path("checks").get(1).path("details").path("port").asInt()).isEqualTo(refusedPort);
    try (ServerSocket corrected = new ServerSocket(0)) {
      connection.withObject("values").put("outboundPort", corrected.getLocalPort());
      CompletableFuture<Integer> exchange = respond(corrected, 0x06);
      ObjectNode result = probe.execute(mapper.createObjectNode().put("requestId", "corrected"), connection, profile);
      assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(result.path("checks").get(1).path("details").path("portSource").asText()).isEqualTo(
        "Connection override"
      );
      assertThat(exchange.get(3, TimeUnit.SECONDS)).isEqualTo(0x04);
    }
  }

  @Test
  void unresponsiveAnalyzerTimesOutDespiteHealthyInboundListener() throws Exception {
    try (ServerSocket remote = new ServerSocket(0)) {
      defaults.setAstmPort(remote.getLocalPort());
      remote.setSoTimeout(3000);
      CompletableFuture<Integer> exchange = new CompletableFuture<>();
      Thread.ofPlatform()
        .start(() -> {
          try (Socket socket = remote.accept()) {
            socket.setSoTimeout(3000);
            assertThat(socket.getInputStream().read()).isEqualTo(0x05);
            exchange.complete(socket.getInputStream().read());
          } catch (Throwable failure) {
            exchange.completeExceptionally(failure);
          }
        });
      ObjectNode result = probe.execute(mapper.createObjectNode().put("requestId", "timeout"), connection, profile);
      assertThat(result.path("status").asText()).isEqualTo("TIMEOUT");
      assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("PASSED");
      assertThat(result.path("checks").get(1).path("messageKey").asText()).isEqualTo("remote.timeout");
      assertThat(exchange.get(3, TimeUnit.SECONDS)).isEqualTo(-1);
    }
  }

  private CompletableFuture<Integer> respond(ServerSocket server, int response) throws Exception {
    server.setSoTimeout(3000);
    CompletableFuture<Integer> completed = new CompletableFuture<>();
    Thread.ofPlatform()
      .start(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(3000);
          assertThat(socket.getInputStream().read()).isEqualTo(0x05);
          socket.getOutputStream().write(response);
          socket.getOutputStream().flush();
          completed.complete(socket.getInputStream().read());
        } catch (Throwable failure) {
          completed.completeExceptionally(failure);
        }
      });
    return completed;
  }
}
