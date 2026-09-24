package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.connectivity.ProbeCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyzerConnectionProbeTest {

  private ObjectMapper objectMapper;
  private ConnectionProbeExecutor executor;
  private AnalyzerConnectionProbe probe;
  private org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties listenerConfig;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    executor = mock(ConnectionProbeExecutor.class);
    listenerConfig = new org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties();
    probe = new AnalyzerConnectionProbe(
      objectMapper,
      Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC),
      executor,
      (protocol, port) -> false,
      new AnalyzerListenerPorts(
        listenerConfig,
        new org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties(),
        new org.itech.ahb.mllp.MLLPConfig()
      )
    );
  }

  @Test
  void inboundHttpRequiresDeliveryEvidenceRatherThanADirectoryOrRemoteProbe() {
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "HTTP").put("host", "192.0.2.25");
    ObjectNode result = probe.execute(request(), connection, profile("FILE"));
    assertThat(result.path("status").asText()).isEqualTo("FAILED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("http.input.verify.with.delivery");
    verifyNoInteractions(executor);
  }

  @Test
  void probesAFileConnectionUsingOnlyItsSavedDirectory() {
    when(executor.probeDirectory("/bridge/inbox")).thenReturn(
      check("DIRECTORY", "PASSED", "directory.ready", Map.of("path", "/bridge/inbox"))
    );
    ObjectNode connection = connection();
    connection.withObject("values").put("directory", "/bridge/inbox");

    ObjectNode result = probe.execute(request(), connection, profile("FILE"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("directory");
    assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("PASSED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("directory.ready");
    assertThat(result.path("checks").get(0).path("details").path("path").asText()).isEqualTo("/bridge/inbox");
    verify(executor).probeDirectory("/bridge/inbox");
  }

  @Test
  void probesAnAstmClientConnectionUsingItsSavedRemoteEndpoint() {
    when(executor.probeRemote("ASTM", "192.0.2.10", 5000, 5_000)).thenReturn(
      check("REMOTE_PROTOCOL", "PASSED", "remote.ready", Map.of("port", 5000))
    );
    ObjectNode connection = connection();
    connection
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "CLIENT")
      .put("host", "192.0.2.10")
      .put("port", 5000);

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("remote-protocol");
    verify(executor).probeRemote("ASTM", "192.0.2.10", 5000, 5_000);
  }

  @Test
  void probesAnAstmServerConnectionUsingItsDeploymentListenerPort() {
    listenerConfig.setPort(5001);
    when(executor.probeListener(5001)).thenReturn(check("LISTENER", "PASSED", "listener.ready", Map.of("port", 5001)));
    when(executor.probeHost("192.0.2.40", 5_000)).thenReturn(
      check("ANALYZER", "PASSED", "analyzer.reachable", Map.of("host", "192.0.2.40"))
    );
    ObjectNode connection = connection();
    connection
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "SERVER")
      .put("port", 5001)
      .put("host", "192.0.2.40");

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("listener");
    assertThat(result.path("checks").get(1).path("key").asText()).isEqualTo("analyzer");
    verify(executor).probeListener(5001);
    verify(executor).probeHost("192.0.2.40", 5_000);
  }

  @Test
  void probesAnActiveAstmServerThroughItsRunningBridgeListener() {
    listenerConfig.setPort(5001);
    when(executor.probeRemote("ASTM", "127.0.0.1", 5001, 5_000)).thenReturn(
      check("REMOTE_PROTOCOL", "PASSED", "remote.astm.ready", Map.of("port", 5001))
    );
    when(executor.probeHost("192.0.2.40", 5_000)).thenReturn(
      check("ANALYZER", "PASSED", "analyzer.reachable", Map.of("host", "192.0.2.40"))
    );
    ObjectNode connection = connection();
    connection
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "SERVER")
      .put("port", 5001)
      .put("host", "192.0.2.40");
    connection.put("actualRuntimeState", "ACTIVE");
    ObjectNode activeRuntimeRef = connection.putObject("activeRuntimeRef");
    activeRuntimeRef.set("profileRef", connection.path("profileRef").deepCopy());
    activeRuntimeRef.put("configRevision", 2);
    activeRuntimeRef.put("configFingerprint", "sha256:" + "3".repeat(64));

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("listener");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.ready");
    assertThat(result.path("checks").get(1).path("key").asText()).isEqualTo("analyzer");
    verify(executor).probeRemote("ASTM", "127.0.0.1", 5001, 5_000);
    verify(executor).probeHost("192.0.2.40", 5_000);
  }

  @Test
  void aPortTheBridgeAlreadyServesIsReadyWithoutBindingItAndTheProtocolIsPassedOn() {
    java.util.List<String> asked = new java.util.ArrayList<>();
    AnalyzerConnectionProbe servedProbe = new AnalyzerConnectionProbe(
      objectMapper,
      Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC),
      executor,
      (protocol, port) -> asked.add(protocol + ":" + port)
    );
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", 5001);

    ObjectNode result = servedProbe.execute(request(), connection, profile("HL7"));

    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("listener.ready");
    assertThat(asked).containsExactly("HL7:2575");
    verify(executor, org.mockito.Mockito.never()).probeListener(5001);
  }

  @Test
  void aServerConnectionWithoutAPortChecksTheDeploymentListener() {
    when(executor.probeListener(12001)).thenReturn(
      check("LISTENER", "PASSED", "listener.ready", Map.of("port", 12001))
    );
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER");
    ObjectNode profile = profile("ASTM");
    profile.withObject("protocol").put("lowerLayerVersion", "LIS01_A");

    ObjectNode result = probe.execute(request(), connection, profile);

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("details").path("port").asInt()).isEqualTo(12001);
    verify(executor).probeListener(12001);
  }

  @Test
  void theAnalyzerCheckIsAdvisoryAndTheListenerDecides() {
    when(executor.probeListener(5001)).thenReturn(check("LISTENER", "PASSED", "listener.ready", Map.of("port", 5001)));
    when(executor.probeListener(5002)).thenReturn(
      check("LISTENER", "FAILED", "listener.port.in.use", Map.of("port", 5002))
    );
    when(executor.probeHost("192.0.2.40", 5_000)).thenReturn(
      check("ANALYZER", "FAILED", "analyzer.unreachable", Map.of("host", "192.0.2.40"))
    );
    when(executor.probeHost("192.0.2.41", 5_000)).thenReturn(
      check("ANALYZER", "PASSED", "analyzer.reachable", Map.of("host", "192.0.2.41"))
    );

    assertThat(overallFor(5001, "192.0.2.40")).as("listener ready, analyzer unreachable").isEqualTo("SUCCEEDED");
    assertThat(overallFor(5002, "192.0.2.41")).as("listener in use, analyzer reachable").isEqualTo("FAILED");
  }

  private String overallFor(int port, String host) {
    listenerConfig.setPort(port);
    ObjectNode connection = connection();
    connection
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "SERVER")
      .put("port", port)
      .put("host", host);
    return probe.execute(request(), connection, profile("ASTM")).path("status").asText();
  }

  @Test
  void theConfiguredProbeAsksEachProtocolsOwnListeners() {
    AstmConnectionListeners astm = mock(AstmConnectionListeners.class);
    Hl7ConnectionListeners hl7 = mock(Hl7ConnectionListeners.class);
    when(astm.isListening(12001)).thenReturn(true);
    when(hl7.isListening(2575)).thenReturn(true);
    when(executor.probeListener(org.mockito.ArgumentMatchers.anyInt())).thenReturn(
      check("LISTENER", "FAILED", "listener.port.in.use", Map.of())
    );
    AnalyzerConnectionProbe configured = new AnalyzerConnectionConfiguration()
      .analyzerConnectionProbe(
        objectMapper,
        Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC),
        executor,
        astm,
        hl7,
        AnalyzerListenerPorts.defaults(),
        new AnalyzerOutboundEndpoint(new AnalyzerOutboundDefaults())
      );

    assertThat(listenerKey(configured, "ASTM", 12001)).isEqualTo("listener.ready");
    assertThat(listenerKey(configured, "HL7", 2575)).isEqualTo("listener.ready");
    assertThat(listenerKey(configured, "HL7", 12001))
      .as("a saved port cannot replace the configured HL7 listener")
      .isEqualTo("listener.ready");
    assertThat(listenerKey(configured, "FILE-OVER-TCP", 12001)).isEqualTo("listener.port.in.use");
  }

  private String listenerKey(AnalyzerConnectionProbe configured, String protocol, int port) {
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", port);
    return configured
      .execute(request(), connection, profile(protocol))
      .path("checks")
      .get(0)
      .path("messageKey")
      .asText();
  }

  @Test
  void probesAnRs232ConnectionUsingItsSavedSerialPort() {
    when(executor.probeSerialDevice("/dev/ttyUSB0")).thenReturn(
      check("SERIAL_DEVICE", "PASSED", "serial.ready", Map.of("path", "/dev/ttyUSB0"))
    );
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "RS-232").put("serialPort", "/dev/ttyUSB0");

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("serial-device");
    verify(executor).probeSerialDevice("/dev/ttyUSB0");
  }

  @Test
  void blocksWithoutCallingTheExecutorWhenTheSavedEndpointIsIncomplete() {
    ObjectNode connection = connection();
    connection.withObject("values").put("transport", "TCP/IP").put("connectionRole", "CLIENT");

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("BLOCKED");
    assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("SKIPPED");
    verifyNoInteractions(executor);
  }

  @Test
  void preservesTimeoutAsOverallProbeEvidence() {
    when(executor.probeRemote("ASTM", "192.0.2.10", 5000, 5_000)).thenReturn(
      check("REMOTE_PROTOCOL", "TIMED_OUT", "remote.timeout", Map.of())
    );
    ObjectNode connection = connection();
    connection
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "CLIENT")
      .put("host", "192.0.2.10")
      .put("port", 5000);

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("TIMEOUT");
    assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("FAILED");
  }

  private ProbeCheck check(String kind, String status, String code, Map<String, Object> details) {
    return new ProbeCheck(kind, status, code, 7, details);
  }

  private ObjectNode request() {
    return objectMapper.createObjectNode().put("requestId", "probe-1");
  }

  private ObjectNode profile(String protocol) {
    ObjectNode profile = objectMapper.createObjectNode();
    profile.putObject("protocol").put("name", protocol).put("lowerLayerVersion", "LIS01_A");
    return profile;
  }

  private ObjectNode connection() {
    ObjectNode connection = objectMapper.createObjectNode();
    connection.put("connectionId", "bridge-42");
    ObjectNode profileRef = connection.putObject("profileRef");
    profileRef.put("profileId", "synthetic-profile");
    profileRef.put("revision", 1);
    profileRef.put("fingerprint", "sha256:" + "1".repeat(64));
    connection.put("configRevision", 3);
    connection.put("configFingerprint", "sha256:" + "3".repeat(64));
    connection.putObject("values");
    return connection;
  }
}
