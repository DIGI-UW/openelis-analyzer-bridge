package org.itech.ahb.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OGC-1054 established analyzer profile contract")
class AnalyzerProfileContractTest {

  private static final Path CONTRACT_ROOT = Path.of("contracts", "analyzer", "v1");
  private static final Path FIXTURE_ROOT = CONTRACT_ROOT.resolve("fixtures");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchema PROFILE_SCHEMA = loadSchema("analyzer-profile.schema.json");
  private static final List<String> PROFILE_FIXTURES = List.of(
    "analyzer-profile-astm.json",
    "analyzer-profile-file.json"
  );

  @Test
  @DisplayName("complete established profiles conform through one generic contract path")
  void completeEstablishedProfilesConform() throws IOException {
    for (String fixtureName : PROFILE_FIXTURES) {
      JsonNode profile = fixture(fixtureName);
      assertTrue(
        PROFILE_SCHEMA.validate(profile).isEmpty(),
        () -> fixtureName + " violates analyzer-profile.schema.json: " + PROFILE_SCHEMA.validate(profile)
      );
      assertSemanticProfile(profile);
    }
  }

  @Test
  @DisplayName("every profile retains runtime communication and analyzer-instance defaults")
  void profilesRetainBothResponsibilities() throws IOException {
    for (String fixtureName : PROFILE_FIXTURES) {
      JsonNode profile = fixture(fixtureName);
      assertTrue(profile.path("protocol").isObject(), fixtureName);
      assertFalse(profile.path("protocol").path("name").asText().isBlank(), fixtureName);
      assertTrue(profile.path("configDefaults").isObject(), fixtureName);
      assertTrue(profile.path("configDefaults").size() > 0, fixtureName);
      assertTrue(profile.path("default_test_mappings").isArray(), fixtureName);

      if ("FILE".equals(profile.path("protocol").path("name").asText())) {
        assertTrue(profile.path("supported_extensions").size() > 0, fixtureName);
        assertTrue(profile.path("column_mapping").size() > 0, fixtureName);
        assertFalse(profile.path("configDefaults").path("filePattern").asText().isBlank(), fixtureName);
      } else {
        assertTrue(profile.path("default_test_mappings").size() > 0, fixtureName);
        assertFalse(profile.path("protocol").path("version").asText().isBlank(), fixtureName);
        assertTrue(profile.path("transport").size() > 0, fixtureName);
        assertFalse(profile.path("communication").path("mode").asText().isBlank(), fixtureName);
        assertFalse(profile.path("configDefaults").path("connectionRole").asText().isBlank(), fixtureName);
        if ("ASTM".equals(profile.path("protocol").path("name").asText())) {
          assertEquals("LIS01_A", profile.path("protocol").path("lowerLayerVersion").asText(), fixtureName);
        }
      }
    }
  }

  @Test
  @DisplayName("ASTM profiles declare the Bridge lower-layer listener they require")
  void astmProfilesRequireASupportedLowerLayerVersion() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-astm.json").deepCopy();
    ObjectNode protocol = (ObjectNode) profile.path("protocol");

    assertEquals("LIS01_A", protocol.path("lowerLayerVersion").asText());

    protocol.remove("lowerLayerVersion");
    assertFalse(PROFILE_SCHEMA.validate(profile).isEmpty());

    protocol.put("lowerLayerVersion", "UNKNOWN");
    assertFalse(PROFILE_SCHEMA.validate(profile).isEmpty());
  }

  @Test
  @DisplayName("RS-232 support declares every runtime setting in the profile")
  void rs232TransportSettingsAreCompleteAndClosed() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-astm.json").deepCopy();
    ObjectNode settings = (ObjectNode) profile.path("transport_config").path("RS-232");
    List<String> requiredSettings = List.of(
      "default_baud_rate",
      "data_bits",
      "stop_bits",
      "parity",
      "flow_control",
      "read_timeout_ms",
      "message_timeout_ms",
      "reconnect_interval_ms",
      "max_reconnect_attempts",
      "rts_enabled",
      "dtr_enabled"
    );

    for (String setting : requiredSettings) {
      ObjectNode missing = profile.deepCopy();
      ((ObjectNode) missing.path("transport_config").path("RS-232")).remove(setting);
      assertFalse(PROFILE_SCHEMA.validate(missing).isEmpty(), setting + " must be required");
    }

    settings.put("invented_setting", true);
    assertFalse(PROFILE_SCHEMA.validate(profile).isEmpty());
  }

  @Test
  @DisplayName("the evolved contract does not invent a model requirement for socket profiles")
  void socketProfileDoesNotRequireAnInventedModel() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-astm.json").deepCopy();
    profile.remove("model");

    assertTrue(PROFILE_SCHEMA.validate(profile).isEmpty(), PROFILE_SCHEMA.validate(profile).toString());
  }

  @Test
  @DisplayName("FILE profiles retain established metadata identity and column-only defaults")
  void fileProfileMayBeColumnOnly() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-file.json").deepCopy();
    profile.remove(List.of("analyzer_name", "manufacturer", "model"));
    ((ObjectNode) profile.path("profileMeta")).put("manufacturer", "fixture-manufacturer");
    profile.withArray("default_test_mappings").removeAll();
    ((ObjectNode) profile.path("protocol")).put("format", "XML");
    ObjectNode defaults = (ObjectNode) profile.path("configDefaults");
    defaults.put("fileFormat", "XML");
    defaults.remove("hasHeader");

    assertTrue(PROFILE_SCHEMA.validate(profile).isEmpty(), PROFILE_SCHEMA.validate(profile).toString());
  }

  @Test
  @DisplayName("FILE profiles can own generic spreadsheet header detection")
  void fileProfileMayDeclareSpreadsheetHeaderDetection() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-file.json").deepCopy();
    ObjectNode detection = profile.putObject("sheet_detection");
    detection.put("strategy", "header_scan");
    detection.putArray("preferred_sheet_names").add("Results");
    detection.put("header_marker", "Specimen");
    detection.put("max_sheets_to_scan", 5);
    detection.put("max_rows_to_scan", 100);

    assertTrue(PROFILE_SCHEMA.validate(profile).isEmpty(), PROFILE_SCHEMA.validate(profile).toString());
  }

  @Test
  @DisplayName("a new profile may start without analyzer test concepts")
  void profileMayStartWithoutDefaultTestMappings() throws IOException {
    ObjectNode profile = fixture("analyzer-profile-astm.json").deepCopy();
    profile.withArray("default_test_mappings").removeAll();

    assertTrue(PROFILE_SCHEMA.validate(profile).isEmpty(), PROFILE_SCHEMA.validate(profile).toString());
    assertSemanticProfile(profile);
  }

  @Test
  @DisplayName("control recognition is explicit RULES or affirmed NONE")
  void controlRecognitionIsDiscriminated() throws IOException {
    ObjectNode rulesProfile = fixture(PROFILE_FIXTURES.get(0)).deepCopy();
    assertEquals("RULES", rulesProfile.path("controlResultRecognition").path("mode").asText());
    assertTrue(rulesProfile.path("controlResultRecognition").path("rules").size() > 0);
    assertFalse(rulesProfile.path("configDefaults").has("qcRules"));

    ObjectNode missing = rulesProfile.deepCopy();
    missing.remove("controlResultRecognition");
    assertFalse(PROFILE_SCHEMA.validate(missing).isEmpty());

    ObjectNode emptyRules = rulesProfile.deepCopy();
    ((ObjectNode) emptyRules.path("controlResultRecognition").path("rules")).removeAll();
    assertFalse(PROFILE_SCHEMA.validate(emptyRules).isEmpty());

    ObjectNode noneProfile = rulesProfile.deepCopy();
    ObjectNode none = JSON.createObjectNode();
    none.put("mode", "NONE");
    none.put("affirmedNoControlResults", true);
    noneProfile.set("controlResultRecognition", none);
    assertTrue(PROFILE_SCHEMA.validate(noneProfile).isEmpty());

    none.put("affirmedNoControlResults", false);
    assertFalse(PROFILE_SCHEMA.validate(noneProfile).isEmpty());
  }

  @Test
  @DisplayName("catalog-generated revision state is separate from authored profile behavior")
  void catalogMetadataIsSeparate() throws IOException {
    for (String fixtureName : PROFILE_FIXTURES) {
      JsonNode profile = fixture(fixtureName);
      assertFalse(profile.has("revision"), fixtureName);
      assertFalse(profile.has("revisionFingerprint"), fixtureName);
      assertFalse(profile.has("source"), fixtureName);
      assertFalse(profile.has("status"), fixtureName);
      assertTrue(profile.path("catalog").path("revision").asInt() > 0, fixtureName);
      assertTrue(
        profile.path("catalog").path("revisionFingerprint").asText().matches("sha256:[0-9a-f]{64}"),
        fixtureName
      );
      assertTrue(
        profile.path("catalog").path("recognitionFingerprint").asText().matches("sha256:[0-9a-f]{64}"),
        fixtureName
      );
    }
  }

  @Test
  @DisplayName("catalog responses expose the established analyzer profile without an adapter")
  void catalogResponsesUseEstablishedProfiles() throws IOException {
    JsonNode entrySchema = JSON.readTree(CONTRACT_ROOT.resolve("profile-catalog-entry.schema.json").toFile());
    assertEquals(
      "https://openelis-global.org/contracts/analyzer/v1/analyzer-profile.schema.json",
      entrySchema.path("properties").path("profile").path("$ref").asText()
    );

    JsonNode response = fixture("profile-catalog-response.json");
    JsonNode profile = response.path("profiles").path(0).path("profile");
    assertEquals(fixture("analyzer-profile-file.json"), profile);
    assertTrue(PROFILE_SCHEMA.validate(profile).isEmpty(), PROFILE_SCHEMA.validate(profile).toString());
    assertFalse(profile.has("profileId"));
    assertFalse(profile.has("revision"));
    assertTrue(profile.path("profileMeta").path("id").isTextual());
    assertTrue(profile.path("catalog").path("revision").isInt());
  }

  @Test
  @DisplayName("profiles exclude OpenELIS bindings, instance secrets, and operational QC")
  void profilesExcludeForeignAuthority() throws IOException {
    ObjectNode profile = fixture(PROFILE_FIXTURES.get(0)).deepCopy();
    profile.put("labUnitId", "not-profile-data");
    assertFalse(PROFILE_SCHEMA.validate(profile).isEmpty());
  }

  @Test
  @DisplayName("semantic validation rejects duplicate raw identities and qualitative values")
  void mappingSemanticsAreDeterministic() throws IOException {
    ObjectNode duplicateCode = fixture(PROFILE_FIXTURES.get(0)).deepCopy();
    duplicateCode
      .withArray("default_test_mappings")
      .add(duplicateCode.path("default_test_mappings").path(0).deepCopy());
    assertFalse(semanticViolations(duplicateCode).isEmpty());

    ObjectNode duplicateValue = fixture(PROFILE_FIXTURES.get(0)).deepCopy();
    JsonNode qualitative = duplicateValue.withArray("default_test_mappings").findValue("values");
    ((com.fasterxml.jackson.databind.node.ArrayNode) qualitative).add(qualitative.path(0).asText());
    assertFalse(semanticViolations(duplicateValue).isEmpty());
  }

  private static void assertSemanticProfile(JsonNode profile) {
    List<String> violations = semanticViolations(profile);
    assertTrue(violations.isEmpty(), () -> "semantic profile violations: " + violations);
  }

  private static List<String> semanticViolations(JsonNode profile) {
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();
    Set<String> rawIdentities = new HashSet<>();

    for (JsonNode mapping : profile.path("default_test_mappings")) {
      String code = mapping.path("test_code").asText();
      if (!rawIdentities.add(code)) {
        violations.add("duplicate analyzer identity: " + code);
      }
      for (JsonNode alias : mapping.path("aliases")) {
        if (!rawIdentities.add(alias.asText())) {
          violations.add("duplicate analyzer identity: " + alias.asText());
        }
      }

      Set<String> values = new HashSet<>();
      for (JsonNode value : mapping.path("values")) {
        if (!values.add(value.asText())) {
          violations.add("duplicate raw result value for " + code + ": " + value.asText());
        }
      }
      if ("qualitative".equals(mapping.path("result_type").asText()) && values.isEmpty()) {
        violations.add("qualitative result has no raw values: " + code);
      }
    }

    return List.copyOf(violations);
  }

  private static JsonNode fixture(String name) throws IOException {
    return JSON.readTree(FIXTURE_ROOT.resolve(name).toFile());
  }

  private static JsonSchema loadSchema(String name) {
    try {
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        JSON.readTree(CONTRACT_ROOT.resolve(name).toFile())
      );
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
