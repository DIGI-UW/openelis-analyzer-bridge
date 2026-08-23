package org.itech.ahb.connectivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyzerConnectionProbeServiceTest {

  private static final JsonSchema PROBE_SCHEMA = probeSchema();

  @Mock
  ConnectionProbeExecutor executor;

  @Mock
  ReceiverListenerPortResolver listenerPorts;

  private AnalyzerRegistryConfig registry;
  private AnalyzerConnectionProbeService service;
  private AnalyzerEntry analyzer;

  @BeforeEach
  void setUp() {
    registry = new AnalyzerRegistryConfig();
    service = new AnalyzerConnectionProbeService(registry, executor, listenerPorts, "bridge.lab.example");
    analyzer = analyzer("RESULTS_ONLY", Map.of());
    registry.register(analyzer.getSourceId(), analyzer);
  }

  @Test
  void resultsOnlyReceiverChecksTheListenerAndReturnsTheEndpointToConfigure() {
    when(listenerPorts.resolve(analyzer)).thenReturn(java.util.OptionalInt.of(12001));
    when(executor.probeListener(12001))
      .thenReturn(check("LISTENER", "PASSED", "listener.ready", 3));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("analyzerId").asText()).isEqualTo("77");
    assertThat(result.path("profileRef").path("profileId").asText())
      .isEqualTo("genexpert-astm");
    assertThat(result.path("profileRef").path("revision").asInt()).isEqualTo(1);
    assertThat(result.path("desiredStateFingerprint").asText())
      .isEqualTo("sha256:" + "6".repeat(64));
    assertThat(result.path("dataFlow").asText()).isEqualTo("RESULTS_ONLY");
    assertThat(result.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(result.path("configureEndpoint").path("host").asText())
      .isEqualTo("bridge.lab.example");
    assertThat(result.path("configureEndpoint").path("port").asInt()).isEqualTo(12001);
    assertThat(result.path("resultsOnlyAvailable").asBoolean()).isFalse();
    assertThat(result.path("checks")).hasSize(1);
    verify(executor, never()).probeRemote(
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyInt(),
      org.mockito.ArgumentMatchers.anyInt()
    );
  }

  @Test
  void twoWayTimeoutKeepsThePassingResultsOnlyPathVisible() {
    analyzer.setDataFlow("TWO_WAY");
    analyzer.setConnectionSettings(
      Map.of(
        "remoteHost", "10.42.20.10",
        "remotePort", 9600L
      )
    );
    when(listenerPorts.resolve(analyzer)).thenReturn(java.util.OptionalInt.of(12001));
    when(executor.probeListener(12001))
      .thenReturn(check("LISTENER", "PASSED", "listener.ready", 2));
    when(executor.probeRemote("ASTM", "10.42.20.10", 9600, 5000))
      .thenReturn(check("REMOTE_PROTOCOL", "TIMED_OUT", "remote.timeout", 5000));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("TIMEOUT");
    assertThat(result.path("resultsOnlyAvailable").asBoolean()).isTrue();
    assertThat(result.path("checks")).hasSize(2);
    assertThat(result.path("checks").path(1).path("status").asText())
      .isEqualTo("TIMED_OUT");
  }

  @Test
  void missingTwoWaySettingsAreReportedWithoutAConnectionAttempt() {
    analyzer.setDataFlow("TWO_WAY");
    when(listenerPorts.resolve(analyzer)).thenReturn(java.util.OptionalInt.of(12001));
    when(executor.probeListener(12001))
      .thenReturn(check("LISTENER", "PASSED", "listener.ready", 2));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("MISSING_CONFIGURATION");
    assertThat(result.path("resultsOnlyAvailable").asBoolean()).isTrue();
    assertThat(result.path("checks").path(1).path("kind").asText())
      .isEqualTo("REMOTE_PROTOCOL");
    assertThat(result.path("checks").path(1).path("status").asText())
      .isEqualTo("MISSING_CONFIGURATION");
    verify(executor, never()).probeRemote(
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyInt(),
      org.mockito.ArgumentMatchers.anyInt()
    );
  }

  @Test
  void initiatorUsesOnlyTheConfiguredRemoteProtocolEndpoint() {
    analyzer.setConnectionRole("INITIATOR");
    analyzer.setConnectionSettings(
      Map.of("remoteHost", "10.42.20.10", "remotePort", 9600L)
    );
    when(executor.probeRemote("ASTM", "10.42.20.10", 9600, 5000))
      .thenReturn(check("REMOTE_PROTOCOL", "PASSED", "remote.ready", 4));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(result.path("configureEndpoint").path("kind").asText())
      .isEqualTo("NETWORK");
    assertThat(result.path("configureEndpoint").path("host").asText())
      .isEqualTo("10.42.20.10");
    assertThat(result.path("configureEndpoint").path("port").asInt()).isEqualTo(9600);
    assertThat(result.path("checks")).hasSize(1);
    assertThat(result.path("checks").path(0).path("kind").asText())
      .isEqualTo("REMOTE_PROTOCOL");
    verify(executor, never()).probeListener(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void fileUsesOnlyTheRegisteredWatchDirectory() {
    analyzer.setExpectedProtocol("FILE");
    analyzer.setConnectionMode("FILE");
    analyzer.setConnectionSettings(Map.of("directory", "/data/analyzers/incoming"));
    when(executor.probeDirectory("/data/analyzers/incoming"))
      .thenReturn(check("DIRECTORY", "PASSED", "directory.ready", 1));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(result.path("configureEndpoint").path("kind").asText())
      .isEqualTo("DIRECTORY");
    assertThat(result.path("configureEndpoint").path("path").asText())
      .isEqualTo("/data/analyzers/incoming");
    assertThat(result.path("checks")).hasSize(1);
    verify(executor, never()).probeListener(org.mockito.ArgumentMatchers.anyInt());
    verify(executor, never()).probeRemote(
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyString(),
      org.mockito.ArgumentMatchers.anyInt(),
      org.mockito.ArgumentMatchers.anyInt()
    );
  }

  @Test
  void serialUsesOnlyTheRegisteredDevice() {
    analyzer.setConnectionMode("SERIAL");
    analyzer.setConnectionSettings(Map.of("device", "/dev/ttyUSB0"));
    when(executor.probeSerialDevice("/dev/ttyUSB0"))
      .thenReturn(check("SERIAL_DEVICE", "PASSED", "serial.ready", 1));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(result.path("configureEndpoint").path("kind").asText())
      .isEqualTo("DEVICE");
    assertThat(result.path("configureEndpoint").path("path").asText())
      .isEqualTo("/dev/ttyUSB0");
    assertThat(result.path("checks")).hasSize(1);
    verify(executor, never()).probeListener(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void httpInitiatorUsesOnlyTheRegisteredBaseUrl() {
    analyzer.setExpectedProtocol("HL7");
    analyzer.setConnectionMode("HTTP");
    analyzer.setConnectionRole("INITIATOR");
    analyzer.setConnectionSettings(Map.of("baseUrl", "https://analyzer.example/hl7"));
    when(executor.probeHttpEndpoint("https://analyzer.example/hl7", 5000))
      .thenReturn(check("HTTP_ENDPOINT", "PASSED", "http.ready", 7));

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(result.path("configureEndpoint").path("kind").asText())
      .isEqualTo("HTTP");
    assertThat(result.path("configureEndpoint").path("url").asText())
      .isEqualTo("https://analyzer.example/hl7");
    assertThat(result.path("checks")).hasSize(1);
    verify(executor, never()).probeListener(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void receiverWithoutAnAdvertisedBridgeHostReportsMissingConfiguration() {
    service = new AnalyzerConnectionProbeService(registry, executor, listenerPorts, " ");

    ObjectNode result = service.probe("77").orElseThrow();

    assertConforms(result);
    assertThat(result.path("outcome").asText()).isEqualTo("MISSING_CONFIGURATION");
    assertThat(result.path("configureEndpoint").isNull()).isTrue();
    assertThat(result.path("checks").path(0).path("code").asText())
      .isEqualTo("listener.endpoint.missing");
    verify(executor, never()).probeListener(org.mockito.ArgumentMatchers.anyInt());
  }

  private static AnalyzerEntry analyzer(String dataFlow, Map<String, Object> settings) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("77");
    entry.setSourceId("10.42.20.10");
    entry.setName("GeneXpert Lab 1");
    entry.setExpectedProtocol("ASTM");
    entry.setProfileId("genexpert-astm");
    entry.setProfileRevision(1);
    entry.setProfileLowerLayerVersion("LIS01_A");
    entry.setDesiredStateFingerprint("sha256:" + "6".repeat(64));
    entry.setDesiredStatus("ACTIVE");
    entry.setConnectionMode("TCP");
    entry.setConnectionRole("RECEIVER");
    entry.setDataFlow(dataFlow);
    entry.setConnectionSettings(settings);
    return entry;
  }

  private static ProbeCheck check(
    String kind,
    String status,
    String code,
    long responseTimeMs
  ) {
    return new ProbeCheck(kind, status, code, responseTimeMs, Map.of());
  }

  private static void assertConforms(ObjectNode result) {
    assertThat(PROBE_SCHEMA.validate(result)).isEmpty();
  }

  private static JsonSchema probeSchema() {
    try {
      return JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(
          new ObjectMapper().readTree(
            Path.of(
              "contracts",
              "analyzer",
              "v1",
              "connection-probe-result.schema.json"
            ).toFile()
          )
        );
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
