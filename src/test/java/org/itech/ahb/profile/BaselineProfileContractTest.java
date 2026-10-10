package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * The baseline profile contract (schemaVersion 2.0): every rule rejects its violation, and a
 * profile written to the contract is valid as it stands.
 */
class BaselineProfileContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AnalyzerProfileValidator validator = new AnalyzerProfileValidator(objectMapper);

  @Test
  void aProfileWrittenToTheContractIsValidAsItStands() throws Exception {
    assertThat(validator.validationIssues(baseline())).isEmpty();
  }

  @Test
  void aPreBaselineProfileKeepsItsHintsBecauseRevisionsAreImmutable() throws Exception {
    ObjectNode legacy = baseline();
    legacy.put("schemaVersion", "1.0");
    legacy.set(
      "default_test_mappings",
      array(
        """
        [{"test_code":"MTB","loinc":"85362-2","result_type":"qualitative",
          "specimen_type_hint":"Sputum","values":["DETECTED"],
          "result_value_hints":{"DETECTED":"Detected"}}]
        """
      )
    );

    assertThat(validator.validationIssues(legacy)).isEmpty();
  }

  @Test
  void rejectsTheHintsRule1Removes() throws Exception {
    ObjectNode profile = baseline();
    test(profile, 0).put("specimen_type_hint", "Sputum");
    test(profile, 0).putObject("result_value_hints").put("DETECTED", "Detected");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("specimen_type_hint"));
    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("result_value_hints"));
  }

  @Test
  void requiresAResultTypeOnEveryTest() throws Exception {
    ObjectNode profile = baseline();
    test(profile, 1).remove("values");
    test(profile, 1).remove("value_codes");
    test(profile, 1).remove("result_type");

    assertThat(validator.validationIssues(profile)).contains("$.default_test_mappings.RIF.result_type is required");
  }

  @Test
  void aQuantitativeTestMayDeclareValuesOnlyThroughItsCallComponent() throws Exception {
    ObjectNode profile = baseline();
    test(profile, 0).remove("call_component");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("call_component"));
  }

  @Test
  void valueCodesMustNameADeclaredValueAndAStandardSystem() throws Exception {
    ObjectNode undeclared = baseline();
    test(undeclared, 0)
      .withObject("value_codes")
      .putArray("SURPRISE")
      .addObject()
      .put("system", "http://loinc.org")
      .put("code", "LA1-1");
    assertThat(validator.validationIssues(undeclared)).contains(
      "$.default_test_mappings.HIVVL.value_codes names an undeclared value: SURPRISE"
    );

    ObjectNode foreignSystem = baseline();
    test(foreignSystem, 0)
      .withObject("value_codes")
      .withArray("DETECTED")
      .addObject()
      .put("system", "https://example.org/codes")
      .put("code", "42");
    assertThat(validator.validationIssues(foreignSystem)).contains(
      "$.default_test_mappings.HIVVL.value_codes.DETECTED cites a system that is not LOINC, SNOMED CT or CIEL: https://example.org/codes"
    );
  }

  @Test
  void aTranslationMustNameADeclaredValueAndBelongToOneValue() throws Exception {
    ObjectNode undeclared = baseline();
    test(undeclared, 0).withObject("translations").putArray("SURPRISE").add("Sorpresa");
    assertThat(validator.validationIssues(undeclared)).contains(
      "$.default_test_mappings.HIVVL.translations names an undeclared value: SURPRISE"
    );

    ObjectNode shared = baseline();
    test(shared, 0).withObject("translations").withArray("DETECTED").add("NON DÉTECTÉ");
    assertThat(validator.validationIssues(shared)).contains(
      "$.default_test_mappings.HIVVL.translations gives one text to two values: NON DÉTECTÉ"
    );
  }

  @Test
  void componentCodesAndSubIdentitiesAreUniqueWithinATest() throws Exception {
    ObjectNode profile = baseline();
    ArrayNode components = test(profile, 0).withArray("components");
    components.add(components.path(1).deepCopy());

    assertThat(validator.validationIssues(profile)).contains(
      "$.default_test_mappings.HIVVL.components repeats the code LOG",
      "$.default_test_mappings.HIVVL.components repeats the sub_identity &LOG"
    );
  }

  @Test
  void theCallComponentIsADeclaredComponentThatTakesTheMainRecord() throws Exception {
    ObjectNode missing = baseline();
    test(missing, 0).put("call_component", "nowhere");
    assertThat(validator.validationIssues(missing)).contains(
      "$.default_test_mappings.HIVVL.call_component names no declared component: nowhere"
    );

    ObjectNode taken = baseline();
    test(taken, 0).put("call_component", "LOG");
    assertThat(validator.validationIssues(taken)).contains(
      "$.default_test_mappings.HIVVL.call_component must name the component with no sub_identity: LOG"
    );
  }

  @Test
  void aQualitativeComponentDeclaresItsValues() throws Exception {
    ObjectNode profile = baseline();
    ObjectNode analyte = test(profile, 0).withArray("components").addObject();
    analyte.put("code", "ANALYTE-X").put("label", "Analyte X").put("result_type", "qualitative");
    analyte.put("sub_identity", "ANALYTE-X");

    assertThat(validator.validationIssues(profile)).anyMatch(
      issue -> issue.contains("components") && issue.contains("values")
    );
  }

  @Test
  void anAssayNamesItselfAndItsVersionAndASourceNamesItsDocumentAndSection() throws Exception {
    ObjectNode assay = baseline();
    test(assay, 0).withObject("assay").remove("version");
    assertThat(validator.validationIssues(assay)).anyMatch(issue -> issue.contains("version"));

    ObjectNode source = baseline();
    test(source, 0).withObject("source").remove("section");
    assertThat(validator.validationIssues(source)).anyMatch(issue -> issue.contains("section"));
  }

  @Test
  void resultPartsPointAtFieldsOfTheProfilesProtocol() throws Exception {
    ObjectNode unknownPart = baseline();
    parts(unknownPart).put("nonsense", "R.4.1");
    assertThat(validator.validationIssues(unknownPart)).anyMatch(issue -> issue.contains("nonsense"));

    ObjectNode foreignField = baseline();
    parts(foreignField).put("call", "OBX.5.1");
    assertThat(validator.validationIssues(foreignField)).anyMatch(issue -> issue.contains("call"));
  }

  @Test
  void aRunFailureValueIsNeverAlsoAnAnswer() throws Exception {
    ObjectNode profile = baseline();
    test(profile, 0).putArray("run_failure_values").add("ERROR").add("DETECTED");

    assertThat(validator.validationIssues(profile)).contains(
      "$.default_test_mappings.HIVVL.run_failure_values repeats a declared value: DETECTED"
    );
  }

  @Test
  void aRunFailureValueMayHaveTranslationsButNotOneAnAnswerAlsoUses() throws Exception {
    ObjectNode profile = baseline();
    test(profile, 0).withObject("translations").putArray("ERROR").add("ERREUR");
    assertThat(validator.validationIssues(profile)).isEmpty();

    test(profile, 0).withObject("translations").withArray("ERROR").add("NON VALIDE");
    assertThat(validator.validationIssues(profile)).contains(
      "$.default_test_mappings.HIVVL.translations gives one text to two values: NON VALIDE"
    );
  }

  @Test
  void theNumberFormatIsADecimalSeparator() throws Exception {
    ObjectNode profile = baseline();
    profile.withObject("configDefaults").put("numberFormat", ";");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("numberFormat"));
  }

  private ObjectNode baseline() throws Exception {
    return BaselineProfileFixtures.genexpertHivViralLoad(objectMapper);
  }

  private ObjectNode parts(ObjectNode profile) {
    return profile.withObject("configDefaults").withObject("extractionOverrides").withObject("resultParts");
  }

  private ObjectNode test(ObjectNode profile, int index) {
    return (ObjectNode) profile.withArray("default_test_mappings").get(index);
  }

  private JsonNode array(String json) throws Exception {
    return objectMapper.readTree(json);
  }
}
