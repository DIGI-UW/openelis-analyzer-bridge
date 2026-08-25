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

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    executor = mock(ConnectionProbeExecutor.class);
    probe = new AnalyzerConnectionProbe(
      objectMapper,
      Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC),
      executor
    );
  }

  @Test
  void probesAFileConnectionUsingOnlyItsSavedDirectory() {
    when(executor.probeDirectory("/bridge/inbox"))
      .thenReturn(check("DIRECTORY", "PASSED", "directory.ready", Map.of("path", "/bridge/inbox")));
    ObjectNode connection = connection();
    connection.withObject("values").put("directory", "/bridge/inbox");

    ObjectNode result = probe.execute(request(), connection, profile("FILE"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("directory");
    assertThat(result.path("checks").get(0).path("status").asText()).isEqualTo("PASSED");
    assertThat(result.path("checks").get(0).path("messageKey").asText()).isEqualTo("directory.ready");
    assertThat(result.path("checks").get(0).path("details").path("path").asText())
      .isEqualTo("/bridge/inbox");
    verify(executor).probeDirectory("/bridge/inbox");
  }

  @Test
  void probesAnAstmClientConnectionUsingItsSavedRemoteEndpoint() {
    when(executor.probeRemote("ASTM", "192.0.2.10", 5000, 5_000))
      .thenReturn(check("REMOTE_PROTOCOL", "PASSED", "remote.ready", Map.of("port", 5000)));
    ObjectNode connection = connection();
    connection.withObject("values")
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
  void probesAnAstmServerConnectionUsingItsSavedListenerPort() {
    when(executor.probeListener(5001))
      .thenReturn(check("LISTENER", "PASSED", "listener.ready", Map.of("port", 5001)));
    ObjectNode connection = connection();
    connection.withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "SERVER")
      .put("port", 5001);

    ObjectNode result = probe.execute(request(), connection, profile("ASTM"));

    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("checks").get(0).path("key").asText()).isEqualTo("listener");
    verify(executor).probeListener(5001);
  }

  @Test
  void probesAnRs232ConnectionUsingItsSavedSerialPort() {
    when(executor.probeSerialDevice("/dev/ttyUSB0"))
      .thenReturn(check("SERIAL_DEVICE", "PASSED", "serial.ready", Map.of("path", "/dev/ttyUSB0")));
    ObjectNode connection = connection();
    connection.withObject("values")
      .put("transport", "RS-232")
      .put("serialPort", "/dev/ttyUSB0");

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
    when(executor.probeRemote("ASTM", "192.0.2.10", 5000, 5_000))
      .thenReturn(check("REMOTE_PROTOCOL", "TIMED_OUT", "remote.timeout", Map.of()));
    ObjectNode connection = connection();
    connection.withObject("values")
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
    profile.putObject("protocol").put("name", protocol);
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
