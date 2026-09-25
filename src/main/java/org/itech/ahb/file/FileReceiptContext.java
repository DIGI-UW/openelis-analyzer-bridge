package org.itech.ahb.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.TabularFileLayout;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.itech.ahb.profile.TabularResultValueSelection;

/** Immutable receipt evidence, not a second configurable profile or connection authority. */
public record FileReceiptContext(
  int version,
  String sourcePath,
  String extension,
  String connectionId,
  String analyzerId,
  String analyzerName,
  String profileId,
  int profileRevision,
  String profileFingerprint,
  String recognitionFingerprint,
  String selectedTestCode,
  Map<String, String> columnMappings,
  String delimiter,
  int skipRows,
  TabularFileLayout layout,
  TabularResultValueSelection resultSelection,
  ControlResultRecognition recognition,
  Map<String, String> codeToLoinc
) {
  public FileReceiptContext {
    if (version != 1) throw new IllegalArgumentException("Unsupported FILE receipt context version: " + version);
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(extension, "extension");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(analyzerId, "analyzerId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(profileFingerprint, "profileFingerprint");
    columnMappings = columnMappings == null ? Map.of() : Map.copyOf(columnMappings);
    codeToLoinc = codeToLoinc == null ? Map.of() : Map.copyOf(codeToLoinc);
  }

  public static FileReceiptContext capture(Path path, AnalyzerEntry entry, String explicitTestCode) {
    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String code = explicitTestCode != null && !explicitTestCode.isBlank()
      ? explicitTestCode.trim()
      : entry.getFileTestCode();
    return new FileReceiptContext(
      1,
      path.toString(),
      dot < 0 ? "" : name.substring(dot),
      entry.getBridgeConnectionId(),
      entry.getId(),
      entry.getName(),
      entry.getProfileId(),
      entry.getProfileRevision(),
      entry.getProfileFingerprint(),
      entry.getRecognitionFingerprint(),
      code,
      entry.getColumnMappings(),
      entry.getDelimiter(),
      entry.getSkipRows(),
      entry.getTabularFileLayout(),
      entry.getTabularResultValueSelection(),
      entry.getControlResultRecognition(),
      entry.getCodeToLoinc()
    );
  }

  /** Renaming the source is not a reinterpretation. Parser choices, assay and profile pin are. */
  public String interpretationHash(ObjectMapper mapper) {
    ObjectNode values = mapper.valueToTree(this);
    values.remove("sourcePath");
    return new ProfileFingerprintService().canonicalFingerprint(values);
  }
}
