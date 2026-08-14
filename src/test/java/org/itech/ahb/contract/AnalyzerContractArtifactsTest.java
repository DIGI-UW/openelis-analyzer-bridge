package org.itech.ahb.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OGC-1054 v1 analyzer contract artifacts")
class AnalyzerContractArtifactsTest {

  private static final Path CONTRACT_ROOT = Path.of("contracts", "analyzer", "v1");
  private static final Path FIXTURE_ROOT = CONTRACT_ROOT.resolve("fixtures");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final FhirContext FHIR = FhirContext.forR4();
  private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final String RAW_CODE_SYSTEM = "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code";
  private static final String SOURCE_TRANSPORT_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-source-transport";
  private static final String CONTRACT_VERSION_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-contract-version";
  private static final String PROFILE_ID_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-id";
  private static final String PROFILE_REVISION_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-revision";
  private static final String SOURCE_PROTOCOL_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-source-protocol";

  @Test
  @DisplayName("portable profile fixture conforms to the versioned profile schema")
  void portableProfileFixtureConforms() throws IOException {
    assertConforms("portable-profile.schema.json", "portable-profile.json");
  }

  @Test
  @DisplayName("profile catalog entry fixture conforms to the versioned API envelope")
  void profileCatalogEntryFixtureConforms() throws IOException {
    assertConforms("profile-catalog-entry.schema.json", "profile-catalog-entry.json");
    assertTrue(
      validationMessages(
        "portable-profile.schema.json",
        fixture("profile-catalog-entry.json").path("profile")
      ).isEmpty()
    );
  }

  @Test
  @DisplayName("both sides of registration reconciliation conform to one versioned schema")
  void registrationReconciliationFixturesConform() throws IOException {
    assertConforms("registration-sync.schema.json", "registration-initial.json");
    assertConforms("registration-sync.schema.json", "registration-next.json");

    JsonNode initial = fixture("registration-initial.json");
    JsonNode next = fixture("registration-next.json");
    assertEquals("1.0", initial.path("schemaVersion").asText());
    assertEquals("1.0", next.path("schemaVersion").asText());
    assertFalse(initial.path("desiredStateRevision").asText().isBlank());
    assertFalse(next.path("desiredStateRevision").asText().isBlank());
    assertFalse(initial.path("desiredStateRevision").asText().equals(next.path("desiredStateRevision").asText()));
    assertConforms("registration-sync-result.schema.json", "registration-result.json");
  }

  @Test
  @DisplayName("legacy registration fixture remains an explicit migration input")
  void legacyRegistrationFixtureConforms() throws IOException {
    assertConforms("legacy-registration.schema.json", "legacy-registration.json");
  }

  @Test
  @DisplayName("known, unknown, QC, and FILE traffic conform to normalized FHIR v1")
  void normalizedTrafficFixturesConform() throws IOException {
    for (String fixture : new String[] {
      "normalized-known-test.fhir.json",
      "normalized-unknown-test.fhir.json",
      "normalized-unknown-value.fhir.json",
      "normalized-qc.fhir.json",
      "normalized-file.fhir.json"
    }) {
      assertConforms("normalized-fhir-bundle.schema.json", fixture);
      Bundle bundle = FHIR.newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(Bundle.class, Files.readString(FIXTURE_ROOT.resolve(fixture)));
      assertNotNull(bundle);
      assertEquals(Bundle.BundleType.TRANSACTION, bundle.getType());

      JsonNode device = firstResource(fixture(fixture), "Device");
      assertTrue(hasExtension(device, CONTRACT_VERSION_EXTENSION));
      assertTrue(hasExtension(device, PROFILE_ID_EXTENSION));
      assertTrue(hasExtension(device, PROFILE_REVISION_EXTENSION));
      assertTrue(hasExtension(device, SOURCE_PROTOCOL_EXTENSION));
    }
  }

  @Test
  @DisplayName("known and unknown fixtures preserve raw identity without inventing a local binding")
  void normalizedIdentityCasesAreDistinct() throws IOException {
    JsonNode known = firstObservation(fixture("normalized-known-test.fhir.json"));
    assertTrue(hasCoding(known, RAW_CODE_SYSTEM, "WBC"));
    assertTrue(hasCoding(known, "http://loinc.org", "6690-2"));

    JsonNode unknownTest = firstObservation(fixture("normalized-unknown-test.fhir.json"));
    assertTrue(hasCoding(unknownTest, RAW_CODE_SYSTEM, "VENDOR-NEW-42"));
    assertFalse(hasCodingSystem(unknownTest, "http://loinc.org"));

    JsonNode unknownValue = firstObservation(fixture("normalized-unknown-value.fhir.json"));
    assertTrue(hasCoding(unknownValue, RAW_CODE_SYSTEM, "HIV-INTERP"));
    assertEquals("INDETERMINATE-VENDOR-X", unknownValue.path("valueString").asText());
  }

  @Test
  @DisplayName("QC and FILE fixtures carry their required routing context")
  void qcAndFileContextIsExplicit() throws IOException {
    JsonNode qc = firstObservation(fixture("normalized-qc.fhir.json"));
    assertTrue(
      StreamSupport.stream(qc.path("meta").path("tag").spliterator(), false).anyMatch(
        tag -> "QC".equals(tag.path("code").asText())
      )
    );

    JsonNode file = firstObservation(fixture("normalized-file.fhir.json"));
    assertTrue(
      StreamSupport.stream(file.path("extension").spliterator(), false).anyMatch(
        extension ->
          SOURCE_TRANSPORT_EXTENSION.equals(extension.path("url").asText()) &&
          "FILE".equals(extension.path("valueCode").asText())
      )
    );
  }

  @Test
  @DisplayName("compatibility manifest identifies the legacy read path and one-writer cutover")
  void compatibilityManifestIsExplicit() throws IOException {
    JsonNode compatibility = JSON.readTree(CONTRACT_ROOT.resolve("compatibility.json").toFile());
    assertEquals("1.0", compatibility.path("contractVersion").asText());
    assertEquals("ACTIVE_UNVERSIONED_SYNC", compatibility.path("legacyRegistration").path("currentState").asText());
    assertEquals(
      "READ_ONLY_MIGRATION_INPUT",
      compatibility.path("legacyRegistration").path("statusAfterCutover").asText()
    );
    assertEquals("OE-M1", compatibility.path("legacyRegistration").path("oneWriterCutover").asText());
    assertEquals("BR-M1", compatibility.path("oneWriterCutover").path("portableProfileOwnerFrom").asText());
    assertEquals("BR-M4", compatibility.path("normalizedTraffic").path("runtimeConformanceFrom").asText());
  }

  @Test
  @DisplayName("portable profile contract cannot contain OpenELIS local catalog identifiers")
  void portableProfileExcludesLocalCatalogOwnership() throws IOException {
    String profileSchema = Files.readString(CONTRACT_ROOT.resolve("portable-profile.schema.json"));
    assertFalse(profileSchema.contains("openelisTestId"));
    assertFalse(profileSchema.contains("openelisResultOptionId"));
    assertFalse(profileSchema.contains("labUnitId"));

    JsonNode invalid = fixture("portable-profile.json").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("tests").path(0)).put("openelisTestId", "123");
    assertFalse(validationMessages("portable-profile.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("registration carries operational QC evidence without transferring rule evaluation")
  void registrationKeepsOperationalQcInOpenElis() throws IOException {
    String registrationSchema = Files.readString(CONTRACT_ROOT.resolve("registration-sync.schema.json"));
    assertTrue(registrationSchema.contains("operationalQc"));
    assertTrue(registrationSchema.contains("activeRuleIds"));
    assertFalse(registrationSchema.contains("WESTGARD"));
    assertFalse(registrationSchema.contains("mean"));
    assertFalse(registrationSchema.contains("standardDeviation"));
  }

  @Test
  @DisplayName("normalized traffic rejects loss of raw analyzer code or value")
  void normalizedTrafficRequiresRawContext() throws IOException {
    JsonNode withoutRawCode = fixture("normalized-known-test.fhir.json").deepCopy();
    JsonNode codings = firstObservation(withoutRawCode).path("code").path("coding");
    ((com.fasterxml.jackson.databind.node.ArrayNode) codings).remove(0);
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", withoutRawCode).isEmpty());

    JsonNode withoutRawValue = fixture("normalized-known-test.fhir.json").deepCopy();
    JsonNode extensions = firstObservation(withoutRawValue).path("extension");
    ((com.fasterxml.jackson.databind.node.ArrayNode) extensions).remove(0);
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", withoutRawValue).isEmpty());
  }

  private static void assertConforms(String schemaName, String fixtureName) throws IOException {
    Set<ValidationMessage> messages = validationMessages(schemaName, fixture(fixtureName));
    assertTrue(messages.isEmpty(), () -> fixtureName + " violates " + schemaName + ": " + messages);
  }

  private static Set<ValidationMessage> validationMessages(String schemaName, JsonNode instance) throws IOException {
    JsonSchema schema = SCHEMAS.getSchema(JSON.readTree(CONTRACT_ROOT.resolve(schemaName).toFile()));
    return schema.validate(instance);
  }

  private static JsonNode fixture(String name) throws IOException {
    return JSON.readTree(FIXTURE_ROOT.resolve(name).toFile());
  }

  private static JsonNode firstObservation(JsonNode bundle) {
    return firstResource(bundle, "Observation");
  }

  private static JsonNode firstResource(JsonNode bundle, String resourceType) {
    return StreamSupport.stream(bundle.path("entry").spliterator(), false)
      .map(entry -> entry.path("resource"))
      .filter(resource -> resourceType.equals(resource.path("resourceType").asText()))
      .findFirst()
      .orElseThrow();
  }

  private static boolean hasExtension(JsonNode resource, String url) {
    return StreamSupport.stream(resource.path("extension").spliterator(), false).anyMatch(
      extension -> url.equals(extension.path("url").asText())
    );
  }

  private static boolean hasCoding(JsonNode observation, String system, String code) {
    return StreamSupport.stream(observation.path("code").path("coding").spliterator(), false).anyMatch(
      coding -> system.equals(coding.path("system").asText()) && code.equals(coding.path("code").asText())
    );
  }

  private static boolean hasCodingSystem(JsonNode observation, String system) {
    return StreamSupport.stream(observation.path("code").path("coding").spliterator(), false).anyMatch(
      coding -> system.equals(coding.path("system").asText())
    );
  }
}
