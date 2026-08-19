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
  private static final String RESULT_CLASSIFICATION_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-classification";
  private static final String CONTROL_RECOGNITION_EXTENSION =
    "https://openelis-global.org/fhir/StructureDefinition/analyzer-control-recognition";

  @Test
  @DisplayName("both sides of registration reconciliation conform to one versioned schema")
  void registrationReconciliationFixturesConform() throws IOException {
    assertConforms("registration-sync.schema.json", "registration-initial.json");
    assertConforms("registration-sync.schema.json", "registration-next.json");

    JsonNode initial = fixture("registration-initial.json");
    JsonNode next = fixture("registration-next.json");
    assertTrue(initial.path("analyzers").isObject());
    assertTrue(next.path("analyzers").isObject());
    assertEquals("1.0", initial.path("schemaVersion").asText());
    assertEquals("1.0", next.path("schemaVersion").asText());
    assertFalse(initial.path("desiredStateRevision").asText().isBlank());
    assertFalse(next.path("desiredStateRevision").asText().isBlank());
    assertFalse(initial.path("desiredStateRevision").asText().equals(next.path("desiredStateRevision").asText()));
    assertConforms("registration-sync-result.schema.json", "registration-result.json");
  }

  @Test
  @DisplayName("registration acknowledgement identifies the exact pinned candidate")
  void registrationAcknowledgementPinsExactCandidate() throws IOException {
    JsonNode requested = keyedOrArrayEntry(fixture("registration-next.json").path("analyzers"), "42");
    JsonNode acknowledged = keyedOrArrayEntry(fixture("registration-result.json").path("registrations"), "42");

    assertEquals(requested.path("profileRef"), acknowledged.path("profileRef"));
    assertEquals(requested.path("desiredStateFingerprint"), acknowledged.path("desiredStateFingerprint"));
    assertTrue(requested.path("desiredStateFingerprint").asText().matches("sha256:[0-9a-f]{64}"));
    assertFalse(requested.has("siteBindingRevision"));
  }

  @Test
  @DisplayName("known, unknown, control, non-match, NONE, and FILE traffic conform to normalized FHIR v1")
  void normalizedTrafficFixturesConform() throws IOException {
    for (String fixture : new String[] {
      "normalized-known-test.fhir.json",
      "normalized-unknown-test.fhir.json",
      "normalized-unknown-value.fhir.json",
      "normalized-qc.fhir.json",
      "normalized-nonmatch.fhir.json",
      "normalized-none.fhir.json",
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

      JsonNode observation = firstObservation(fixture(fixture));
      assertTrue(hasExtension(observation, RESULT_CLASSIFICATION_EXTENSION));
      assertTrue(hasExtension(observation, CONTROL_RECOGNITION_EXTENSION));
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
    assertEquals("CONTROL", extensionValueCode(qc, RESULT_CLASSIFICATION_EXTENSION));

    JsonNode nonmatch = firstObservation(fixture("normalized-nonmatch.fhir.json"));
    assertEquals("PATIENT", extensionValueCode(nonmatch, RESULT_CLASSIFICATION_EXTENSION));
    assertFalse(
      StreamSupport.stream(nonmatch.path("meta").path("tag").spliterator(), false).anyMatch(
        tag -> "QC".equals(tag.path("code").asText())
      )
    );

    JsonNode none = firstObservation(fixture("normalized-none.fhir.json"));
    assertEquals("PATIENT", extensionValueCode(none, RESULT_CLASSIFICATION_EXTENSION));

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
  @DisplayName("registration excludes OpenELIS operational QC and classifier state")
  void registrationExcludesOperationalQcAndClassifierState() throws IOException {
    String registrationSchema = Files.readString(CONTRACT_ROOT.resolve("registration-sync.schema.json"));
    assertFalse(registrationSchema.contains("operationalQc"));
    assertFalse(registrationSchema.contains("activeRuleIds"));
    assertFalse(registrationSchema.contains("controlLots"));
    assertFalse(registrationSchema.contains("qcRules"));

    JsonNode invalid = fixture("registration-initial.json").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) keyedOrArrayEntry(invalid.path("analyzers"), "42")).set(
        "operationalQc",
        JSON.createObjectNode()
      );
    assertFalse(validationMessages("registration-sync.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("registration settings cannot hide OpenELIS QC or classifier state")
  void registrationSettingsRejectOperationalQcAndClassifierAliases() throws IOException {
    for (String reservedKey : new String[] {
      "controlLot",
      "qcRule",
      "westgardEnabled",
      "classifierState",
      "openelisTestId",
      "labUnitId",
      "testCodeLoinc"
    }) {
      JsonNode invalid = fixture("registration-initial.json").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) keyedOrArrayEntry(invalid.path("analyzers"), "42")
          .path("connection")
          .path("settings")).put(reservedKey, "must-not-cross-boundary");
      assertFalse(
        validationMessages("registration-sync.schema.json", invalid).isEmpty(),
        () -> "registration settings accepted reserved key " + reservedKey
      );
    }
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

  @Test
  @DisplayName("normalized classification and recognition evidence cannot contradict each other")
  void normalizedClassificationAndRecognitionMustAgree() throws IOException {
    JsonNode controlAsPatient = fixture("normalized-qc.fhir.json").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) findExtension(
        firstObservation(controlAsPatient),
        RESULT_CLASSIFICATION_EXTENSION
      )).put("valueCode", "PATIENT");
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", controlAsPatient).isEmpty());

    JsonNode nonmatchAsControl = fixture("normalized-nonmatch.fhir.json").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) findExtension(
        firstObservation(nonmatchAsControl),
        RESULT_CLASSIFICATION_EXTENSION
      )).put("valueCode", "CONTROL");
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", nonmatchAsControl).isEmpty());
  }

  @Test
  @DisplayName("every Observation carries normalized analyzer provenance")
  void normalizedSchemaConstrainsEveryObservation() throws IOException {
    JsonNode invalid = fixture("normalized-known-test.fhir.json").deepCopy();
    com.fasterxml.jackson.databind.node.ArrayNode entries =
      (com.fasterxml.jackson.databind.node.ArrayNode) invalid.path("entry");
    com.fasterxml.jackson.databind.node.ObjectNode extraObservation = StreamSupport.stream(entries.spliterator(), false)
      .filter(entry -> "Observation".equals(entry.path("resource").path("resourceType").asText()))
      .map(JsonNode::deepCopy)
      .map(com.fasterxml.jackson.databind.node.ObjectNode.class::cast)
      .findFirst()
      .orElseThrow();
    ((com.fasterxml.jackson.databind.node.ObjectNode) extraObservation.path("resource")).remove("extension");
    entries.add(extraObservation);

    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("RULES outcomes agree with every rule evaluation")
  void rulesOutcomeRequiresConsistentEvaluationEvidence() throws IOException {
    JsonNode matchWithoutMatchedRule = fixture("normalized-qc.fhir.json").deepCopy();
    setFirstEvaluationMatched(firstObservation(matchWithoutMatchedRule), false);
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", matchWithoutMatchedRule).isEmpty());

    JsonNode noMatchWithMatchedRule = fixture("normalized-nonmatch.fhir.json").deepCopy();
    setFirstEvaluationMatched(firstObservation(noMatchWithMatchedRule), true);
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", noMatchWithMatchedRule).isEmpty());

    JsonNode rulesNotEvaluated = fixture("normalized-nonmatch.fhir.json").deepCopy();
    recognitionPart(firstObservation(rulesNotEvaluated), "outcome").put("valueCode", "NOT_EVALUATED");
    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", rulesNotEvaluated).isEmpty());
  }

  @Test
  @DisplayName("a matching recognition evaluation carries complete rule and source evidence")
  void matchedRecognitionEvaluationRequiresCompleteEvidence() throws IOException {
    JsonNode invalid = fixture("normalized-qc.fhir.json").deepCopy();
    setFirstEvaluationMatched(firstObservation(invalid), false);

    com.fasterxml.jackson.databind.node.ObjectNode incompleteMatch = JSON.createObjectNode();
    incompleteMatch.put("url", "evaluation");
    com.fasterxml.jackson.databind.node.ArrayNode parts = incompleteMatch.putArray("extension");
    parts.addObject().put("url", "matched").put("valueBoolean", true);
    ((com.fasterxml.jackson.databind.node.ArrayNode) findExtension(
        firstObservation(invalid),
        CONTROL_RECOGNITION_EXTENSION
      ).path("extension")).add(incompleteMatch);

    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("each Observation has exactly one control recognition extension")
  void controlRecognitionExtensionIsSingular() throws IOException {
    JsonNode invalid = fixture("normalized-qc.fhir.json").deepCopy();
    JsonNode observation = firstObservation(invalid);
    ((com.fasterxml.jackson.databind.node.ArrayNode) observation.path("extension")).add(
        findExtension(observation, CONTROL_RECOGNITION_EXTENSION).deepCopy()
      );

    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("registration acknowledgement has one result per OpenELIS analyzer")
  void registrationAcknowledgementIsKeyedByAnalyzer() throws IOException {
    JsonNode result = fixture("registration-result.json");
    assertTrue(result.path("registrations").isObject());
    assertEquals(2, result.path("registrations").size());

    JsonNode invalid = result.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).set(
        "registrations",
        JSON.createArrayNode().add(result.path("registrations").path("42"))
      );
    assertFalse(validationMessages("registration-sync-result.schema.json", invalid).isEmpty());
  }

  @Test
  @DisplayName("NONE recognition has exactly one NOT_EVALUATED outcome")
  void noneRecognitionRejectsAdditionalOutcome() throws IOException {
    JsonNode invalid = fixture("normalized-none.fhir.json").deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode extraOutcome = JSON.createObjectNode();
    extraOutcome.put("url", "outcome");
    extraOutcome.put("valueCode", "MATCH");
    ((com.fasterxml.jackson.databind.node.ArrayNode) findExtension(
        firstObservation(invalid),
        CONTROL_RECOGNITION_EXTENSION
      ).path("extension")).add(extraOutcome);

    assertFalse(validationMessages("normalized-fhir-bundle.schema.json", invalid).isEmpty());
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

  private static JsonNode keyedOrArrayEntry(JsonNode collection, String key) {
    if (collection.isObject()) {
      return collection.path(key);
    }
    return StreamSupport.stream(collection.spliterator(), false)
      .filter(entry -> key.equals(entry.path("oeAnalyzerId").asText()))
      .findFirst()
      .orElseThrow();
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

  private static String extensionValueCode(JsonNode resource, String url) {
    return StreamSupport.stream(resource.path("extension").spliterator(), false)
      .filter(extension -> url.equals(extension.path("url").asText()))
      .map(extension -> extension.path("valueCode").asText())
      .findFirst()
      .orElse("");
  }

  private static JsonNode findExtension(JsonNode resource, String url) {
    return StreamSupport.stream(resource.path("extension").spliterator(), false)
      .filter(extension -> url.equals(extension.path("url").asText()))
      .findFirst()
      .orElseThrow();
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode recognitionPart(JsonNode observation, String url) {
    JsonNode recognition = findExtension(observation, CONTROL_RECOGNITION_EXTENSION);
    return StreamSupport.stream(recognition.path("extension").spliterator(), false)
      .filter(extension -> url.equals(extension.path("url").asText()))
      .map(com.fasterxml.jackson.databind.node.ObjectNode.class::cast)
      .findFirst()
      .orElseThrow();
  }

  private static void setFirstEvaluationMatched(JsonNode observation, boolean matched) {
    JsonNode evaluation = recognitionPart(observation, "evaluation");
    StreamSupport.stream(evaluation.path("extension").spliterator(), false)
      .filter(part -> "matched".equals(part.path("url").asText()))
      .map(com.fasterxml.jackson.databind.node.ObjectNode.class::cast)
      .findFirst()
      .orElseThrow()
      .put("valueBoolean", matched);
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
