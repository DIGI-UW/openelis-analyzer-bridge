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
    verify(watcher).addWatchDirectory(
      replacementDirectory,
      "*.{ods,ODS,xlsx,XLSX,xls,XLS}",
      "oe-42"
    );
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
    verify(watcher, times(2)).addWatchDirectory(
      originalDirectory,
      "*.{ods,ODS,xlsx,XLSX,xls,XLS}",
      "oe-42"
    );
    assertThat(registry.getRegisteredAnalyzers())
      .containsOnlyKeys(originalDirectory + "#00000000-0000-0000-0000-000000000042");
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
    connection.withObject("values")
      .setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("port", 9_101);

    runtime.activate(connection, profile);

    verify(astmListeners).start(
      "00000000-0000-0000-0000-000000000042",
      "connection:00000000-0000-0000-0000-000000000042",
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
    assertThat(entry.getAstmResultRecordSelection()).isNotNull();
    assertThat(entry.getControlResultRecognition()).isNotNull();

    runtime.deactivate(connection, profile);

    verify(astmListeners).stop("00000000-0000-0000-0000-000000000042");
    assertThat(registry.getRegisteredAnalyzers().values()).noneMatch(candidate -> "oe-42".equals(candidate.getId()));
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
      new SerialConnectionSettings(
        "ASTM",
        9600,
        8,
        1,
        "NONE",
        "NONE",
        1000,
        30000,
        5000,
        -1,
        true,
        true
      )
    );
    assertThat(registry.findAnalyzerId("connection:00000000-0000-0000-0000-000000000042"))
      .contains("oe-42");
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
        "connection:00000000-0000-0000-0000-000000000042",
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
