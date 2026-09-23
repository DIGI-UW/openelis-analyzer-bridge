package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.profile.ControlResultRecognition.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeAnalyzerConnectionRuntimeTest {

  @TempDir
  Path directory;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void activatesAndDeactivatesAFileConnectionFromItsPinnedProfileAndSavedValues() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    FileWatcher watcher = mock(FileWatcher.class);
    AstmConnectionListeners astmListeners = mock(AstmConnectionListeners.class);
    SerialConnectionListeners serialListeners = mock(SerialConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      watcher,
      astmListeners,
      serialListeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/fluorocycler-xt.json")
    );
    ObjectNode connection = connection(profile);

    runtime.activate(connection, profile);

    verify(watcher).addWatchDirectory(directory, "*.{ods,ODS,xlsx,XLSX,xls,XLS}", "oe-42");
    AnalyzerEntry entry = registry
      .getRegisteredAnalyzers()
      .values()
      .stream()
      .filter(candidate -> "oe-42".equals(candidate.getId()))
      .findFirst()
      .orElseThrow();
    assertThat(entry.getExpectedProtocol()).isEqualTo("FILE");
    assertThat(entry.getFileDirectory()).isEqualTo(directory.toString());
    assertThat(entry.getBridgeConnectionId()).isEqualTo("00000000-0000-0000-0000-000000000042");
    assertThat(entry.getProfileId()).isEqualTo(profile.path("profileMeta").path("id").asText());
    assertThat(entry.getProfileRevision()).isEqualTo(profile.path("catalog").path("revision").asInt());
    assertThat(entry.getColumnMappings())
      .containsEntry("Sample ID", "sampleId")
      .containsEntry("TargetName", "testCode")
      .containsEntry("Interpretation", "interpretation");
    assertThat(entry.getControlResultRecognition().mode()).isEqualTo(Mode.RULES);
    assertThat(entry.getControlResultRecognition().rules()).hasSize(5);
    assertThat(entry.getRecognitionFingerprint())
      .isEqualTo(profile.path("catalog").path("recognitionFingerprint").asText());

    runtime.deactivate(connection, profile);

    verify(watcher).removeWatchRegistration(directory, "oe-42");
    assertThat(registry.getRegisteredAnalyzers().values()).noneMatch(candidate -> "oe-42".equals(candidate.getId()));
    verifyNoInteractions(astmListeners);
    verifyNoInteractions(serialListeners);
  }

  @Test
  void reactivatingAChangedFileConnectionReplacesItsPriorRuntimeMaterialization() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    FileWatcher watcher = mock(FileWatcher.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      watcher,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/fluorocycler-xt.json")
    );
    Path originalDirectory = directory.resolve("original");
    Path replacementDirectory = directory.resolve("replacement");
    ObjectNode original = connection(profile);
    original.withObject("values").put("directory", originalDirectory.toString());
    ObjectNode replacement = original.deepCopy();
    replacement.put("configRevision", 2);
    replacement.withObject("values").put("directory", replacementDirectory.toString());

    runtime.activate(original, profile);
    runtime.activate(replacement, profile);

    verify(watcher).removeWatchRegistration(originalDirectory, "oe-42");
    verify(watcher).addWatchDirectory(replacementDirectory, "*.{ods,ODS,xlsx,XLSX,xls,XLS}", "oe-42");
    assertThat(registry.getRegisteredAnalyzers()).hasSize(1);
  }

  @Test
  void failedFileReplacementRestoresThePriorRuntimeMaterialization() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    FileWatcher watcher = mock(FileWatcher.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      watcher,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/fluorocycler-xt.json")
    );
    Path originalDirectory = directory.resolve("original");
    Path replacementDirectory = directory.resolve("replacement");
    ObjectNode original = connection(profile);
    original.withObject("values").put("directory", originalDirectory.toString());
    ObjectNode replacement = original.deepCopy();
    replacement.put("configRevision", 2);
    replacement.withObject("values").put("directory", replacementDirectory.toString());
    doThrow(new AnalyzerConnectionException("replacement unavailable"))
      .when(watcher)
      .addWatchDirectory(replacementDirectory, "*.{ods,ODS,xlsx,XLSX,xls,XLS}", "oe-42");

    runtime.activate(original, profile);

    assertThatThrownBy(() -> runtime.activate(replacement, profile))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessage("replacement unavailable");
    verify(watcher).removeWatchRegistration(originalDirectory, "oe-42");
    verify(watcher, times(2)).addWatchDirectory(originalDirectory, "*.{ods,ODS,xlsx,XLSX,xls,XLS}", "oe-42");
    assertThat(registry.getRegisteredAnalyzers()).containsOnlyKeys(
      originalDirectory + "#00000000-0000-0000-0000-000000000042"
    );
  }

  @Test
  void activatesAndDeactivatesAnAstmServerFromItsPinnedProfileAndSavedPort() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners astmListeners = mock(AstmConnectionListeners.class);
    SerialConnectionListeners serialListeners = mock(SerialConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      astmListeners,
      serialListeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode connection = baseConnection(profile, "GeneXpert bench 1");
    connection.withObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("port", 9_101);

    runtime.activate(connection, profile);

    verify(astmListeners).start(
      "00000000-0000-0000-0000-000000000042",
      "oe-42",
      9_101,
      "LIS01_A"
    );
    AnalyzerEntry entry = registry
      .getRegisteredAnalyzers()
      .values()
      .stream()
      .filter(candidate -> "oe-42".equals(candidate.getId()))
      .findFirst()
      .orElseThrow();
    assertThat(entry.getExpectedProtocol()).isEqualTo("ASTM");
    assertThat(entry.getListenerPort()).isEqualTo(9_101);
    assertThat(entry.getInboundAddress()).isNull();
    assertThat(entry.getAstmResultRecordSelection()).isNotNull();
    assertThat(entry.getControlResultRecognition()).isNotNull();

    runtime.deactivate(connection, profile);

    verify(astmListeners).stop("00000000-0000-0000-0000-000000000042");
    assertThat(registry.getRegisteredAnalyzers().values()).noneMatch(candidate -> "oe-42".equals(candidate.getId()));
    verifyNoInteractions(serialListeners);
  }

  @Test
  void astmServerHostIsCanonicalisedWhenLiteralAndKeptAsEnteredWhenAHostname() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode literal = baseConnection(profile, "GeneXpert bench 1");
    literal.withObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    literal.withObject("values").put("port", 12_001).put("host", " 2001:db8:0:0:0:0:0:21 ");

    runtime.activate(literal, profile);

    AnalyzerEntry entry = registry.getRegisteredAnalyzers().values().iterator().next();
    assertThat(entry.getListenerPort()).isEqualTo(12_001);
    assertThat(entry.getInboundAddress()).isEqualTo("2001:db8:0:0:0:0:0:21");

    ObjectNode hostname = literal.deepCopy();
    hostname.put("configRevision", 2);
    hostname.withObject("values").put("host", "gx-bench-1.lab.local");

    runtime.activate(hostname, profile);

    entry = registry.getRegisteredAnalyzers().values().iterator().next();
    // Never resolved: a hostname cannot claim a peer address, so it never matches on address.
    assertThat(entry.getInboundAddress()).isNull();
    assertThat(entry.getInboundSourceId()).isEqualTo("gx-bench-1.lab.local");
    assertThat(entry.getListenerPort()).isEqualTo(12_001);
  }

  @Test
  void refusesToActivateASecondConnectionThatNoMessageCouldBeToldApartFrom() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode first = sharedPortConnection(profile, "gx-a", "oe-a", "GeneXpert A");
    ObjectNode second = sharedPortConnection(profile, "gx-b", "oe-b", "GeneXpert B");
    runtime.activate(first, profile);

    assertThatThrownBy(() -> runtime.activate(second, profile))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("gx-a")
      .hasMessageContaining("GeneXpert A")
      .hasMessageContaining("senderId");
    assertThat(registry.getRegisteredAnalyzers()).containsOnlyKeys("connection:gx-a");

    second.withObject("values").put("senderId", "GX-LAB-B");
    runtime.activate(second, profile);
    assertThat(registry.getRegisteredAnalyzers()).containsOnlyKeys("connection:gx-a", "connection:gx-b");
    assertThat(registry.findAnalyzerEntryByConnectionId("gx-b").orElseThrow().getSenderId()).isEqualTo("GX-LAB-B");

    ObjectNode third = sharedPortConnection(profile, "gx-c", "oe-c", "GeneXpert C");
    third.withObject("values").put("host", "10.0.0.23");
    runtime.activate(third, profile);
    assertThat(registry.getRegisteredAnalyzers()).containsKey("connection:gx-c");
  }

  @Test
  void restoringAnIndistinguishablePairDoesNotStopTheBridge() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );

    runtime.restore(sharedPortConnection(profile, "gx-a", "oe-a", "GeneXpert A"), profile);
    runtime.restore(sharedPortConnection(profile, "gx-b", "oe-b", "GeneXpert B"), profile);

    assertThat(registry.getRegisteredAnalyzers()).containsOnlyKeys("connection:gx-a", "connection:gx-b");
  }

  private ObjectNode sharedPortConnection(ObjectNode profile, String connectionId, String analyzerId, String name) {
    ObjectNode connection = baseConnection(profile, name);
    connection.put("connectionId", connectionId);
    connection.put("clientAnalyzerId", analyzerId);
    connection.withObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("port", 12_001);
    return connection;
  }

  @Test
  void activatesAndDeactivatesAnHl7ServerWithItsOwnConnectionIdentity() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    Hl7ConnectionListeners listeners = mock(Hl7ConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class),
      listeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      java.nio.file.Files.readString(Path.of("contracts/analyzer/v1/fixtures/analyzer-profile-astm.json"))
    );
    profile.putObject("protocol").put("name", "HL7").put("version", "2.5.1");
    profile.putObject("controlResultRecognition").put("mode", "NONE").put("affirmedNoControlResults", true);
    ObjectNode connection = baseConnection(profile, "HL7 fixture bench");
    connection.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", 9123);

    runtime.activate(connection, profile);
    verify(listeners).start(
      "00000000-0000-0000-0000-000000000042",
      "connection:00000000-0000-0000-0000-000000000042",
      9123
    );
    assertThat(registry.findAnalyzerId("connection:00000000-0000-0000-0000-000000000042")).contains("oe-42");
    runtime.deactivate(connection, profile);
    verify(listeners).stop("00000000-0000-0000-0000-000000000042");
    assertThat(registry.getRegisteredAnalyzers()).isEmpty();
  }

  @Test
  void materializesAClientConnectionAsItsBridgeOwnedOutboundEndpoint() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners astmListeners = mock(AstmConnectionListeners.class);
    SerialConnectionListeners serialListeners = mock(SerialConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      astmListeners,
      serialListeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode connection = baseConnection(profile, "GeneXpert outbound bench");
    connection.withObject("values")
      .setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values")
      .put("connectionRole", "CLIENT")
      .put("host", "gene-xpert.lab")
      .put("port", 9_600);

    runtime.activate(connection, profile);

    AnalyzerEntry entry = registry
      .findAnalyzerEntryByConnectionId("00000000-0000-0000-0000-000000000042")
      .orElseThrow();
    assertThat(entry.getExpectedProtocol()).isEqualTo("ASTM");
    assertThat(entry.getOutboundHost()).isEqualTo("gene-xpert.lab");
    assertThat(entry.getOutboundPort()).isEqualTo(9_600);
    verifyNoInteractions(astmListeners);
    verifyNoInteractions(serialListeners);
  }

  @Test
  void activatesAndDeactivatesAProfileDrivenRs232Connection() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners astmListeners = mock(AstmConnectionListeners.class);
    SerialConnectionListeners serialListeners = mock(SerialConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      astmListeners,
      serialListeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode connection = baseConnection(profile, "GeneXpert serial bench");
    connection.withObject("values").put("transport", "RS-232").put("serialPort", "/dev/ttyUSB7");

    runtime.activate(connection, profile);

    verify(serialListeners).start(
      "00000000-0000-0000-0000-000000000042",
      "connection:00000000-0000-0000-0000-000000000042",
      "oe-42",
      "/dev/ttyUSB7",
      new SerialConnectionSettings("ASTM", 9600, 8, 1, "NONE", "NONE", 1000, 30000, 5000, -1, true, true)
    );
    assertThat(registry.findAnalyzerId("connection:00000000-0000-0000-0000-000000000042")).contains("oe-42");
    verifyNoInteractions(astmListeners);

    runtime.deactivate(connection, profile);

    verify(serialListeners).stop("00000000-0000-0000-0000-000000000042");
    assertThat(registry.getRegisteredAnalyzers().values()).noneMatch(candidate -> "oe-42".equals(candidate.getId()));
  }

  @Test
  void removesTheRuntimeRegistrationWhenAListenerCannotStart() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners astmListeners = mock(AstmConnectionListeners.class);
    SerialConnectionListeners serialListeners = mock(SerialConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      astmListeners,
      serialListeners
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      BridgeAnalyzerConnectionRuntimeTest.class.getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode connection = baseConnection(profile, "GeneXpert bench 1");
    connection.withObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("port", 9_101);
    doThrow(new AnalyzerConnectionException("occupied"))
      .when(astmListeners)
      .start(
        "00000000-0000-0000-0000-000000000042",
        "oe-42",
        9_101,
        "LIS01_A"
      );

    assertThatThrownBy(() -> runtime.activate(connection, profile))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessage("occupied");
    assertThat(registry.getRegisteredAnalyzers()).isEmpty();
  }

  private ObjectNode connection(ObjectNode profile) {
    ObjectNode connection = baseConnection(profile, "Fluoro bench 1");
    ObjectNode values = connection.withObject("values");
    values.put("directory", directory.toString());
    values.put("filePattern", "*.{ods,ODS,xlsx,XLSX,xls,XLS}");
    values.put("fileFormat", "XLSX");
    values.put("hasHeader", true);
    values.put("sheetIndex", 0);
    return connection;
  }

  @Test
  void sameHostClientsRemainIndependentThroughRestoreReplacementAndDeactivation() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners listeners = mock(AstmConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      listeners,
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode first = clientConnection(profile, "connection-a", "oe-a", 9101);
    ObjectNode second = clientConnection(profile, "connection-b", "oe-b", 9102);

    runtime.activate(first, profile);
    assertThat(registry.findAnalyzerId("192.0.2.10")).contains("oe-a");
    runtime.restore(second, profile);
    assertThat(registry.getRegisteredAnalyzers()).hasSize(2);
    assertThat(registry.findAnalyzerId("connection:connection-a")).contains("oe-a");
    assertThat(registry.findAnalyzerId("connection:connection-b")).contains("oe-b");
    assertThat(registry.findAnalyzerId("192.0.2.10")).isEmpty();

    ObjectNode replacement = first.deepCopy();
    replacement.withObject("values").put("port", 9103);
    runtime.activate(replacement, profile);
    assertThat(registry.getRegisteredAnalyzers()).hasSize(2);
    assertThat(registry.findAnalyzerId("connection:connection-b")).contains("oe-b");

    runtime.deactivate(replacement, profile);
    assertThat(registry.findAnalyzerId("connection:connection-a")).isEmpty();
    assertThat(registry.findAnalyzerId("192.0.2.10")).contains("oe-b");
    runtime.deactivate(second, profile);
    assertThat(registry.getRegisteredAnalyzers()).isEmpty();
    verifyNoInteractions(listeners);
  }

  @Test
  void failedReplacementRestoresOneClientWithoutRemovingAnotherOnTheSameHost() throws Exception {
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AstmConnectionListeners listeners = mock(AstmConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      listeners,
      mock(SerialConnectionListeners.class)
    );
    ObjectNode profile = (ObjectNode) objectMapper.readTree(
      getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    ObjectNode first = clientConnection(profile, "connection-a", "oe-a", 9101);
    ObjectNode second = clientConnection(profile, "connection-b", "oe-b", 9102);
    runtime.activate(first, profile);
    runtime.activate(second, profile);

    ObjectNode replacement = first.deepCopy();
    replacement.withObject("values").put("connectionRole", "SERVER");
    doThrow(new AnalyzerConnectionException("occupied"))
      .when(listeners)
      .start("connection-a", "oe-a", 9101, "LIS01_A");
    assertThatThrownBy(() -> runtime.activate(replacement, profile))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessage("occupied");

    assertThat(registry.getRegisteredAnalyzers()).hasSize(2);
    assertThat(registry.findAnalyzerId("connection:connection-a")).contains("oe-a");
    assertThat(registry.findAnalyzerId("connection:connection-b")).contains("oe-b");
    assertThat(registry.findAnalyzerId("192.0.2.10")).isEmpty();
  }

  private ObjectNode clientConnection(ObjectNode profile, String connectionId, String analyzerId, int port) {
    ObjectNode connection = baseConnection(profile, "Client bench " + analyzerId);
    connection.put("connectionId", connectionId).put("clientAnalyzerId", analyzerId);
    connection.withObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("connectionRole", "CLIENT").put("host", "192.0.2.10").put("port", port);
    return connection;
  }

  private ObjectNode baseConnection(ObjectNode profile, String displayName) {
    ObjectNode connection = objectMapper.createObjectNode();
    connection.put("connectionId", "00000000-0000-0000-0000-000000000042");
    connection.put("clientAnalyzerId", "oe-42");
    connection.put("displayName", displayName);
    ObjectNode profileRef = connection.putObject("profileRef");
    profileRef.put("profileId", profile.path("profileMeta").path("id").asText());
    profileRef.put("revision", profile.path("catalog").path("revision").asInt());
    profileRef.put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    connection.put("configRevision", 1);
    connection.put("configFingerprint", "sha256:" + "1".repeat(64));
    connection.putObject("values");
    return connection;
  }
}
