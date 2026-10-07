package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The baseline profile contract (schemaVersion 2.0): every rule rejects its violation, and a
 * profile written to the contract is valid as it stands.
 */
class BaselineProfileContractTest {

  private static final String ASTM_PROFILE = "analyzer-profiles/genexpert-astm-v7.json";

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
    analyte.put("code", "HIV-1").put("label", "HIV-1").put("result_type", "qualitative");
    analyte.put("sub_identity", "HIV-1");

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
    parts(foreignField).put("call", "OBX-5.1");
    assertThat(validator.validationIssues(foreignField)).anyMatch(issue -> issue.contains("call"));
  }

  @Test
  void theNumberFormatIsADecimalSeparator() throws Exception {
    ObjectNode profile = baseline();
    profile.withObject("configDefaults").putObject("numberFormat").put("decimalSeparator", ";");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("decimalSeparator"));
  }

  /**
   * GeneXpert's HIV-1 viral load as the contract wants it written: a number with a call, a log
   * record and an analyte record, each with its sub-identity, sourced to the vendor's document.
   */
  private ObjectNode baseline() throws Exception {
    ObjectNode profile;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(ASTM_PROFILE)) {
      assertThat(input).as(ASTM_PROFILE).isNotNull();
      profile = (ObjectNode) objectMapper.readTree(input);
    }
    profile.put("schemaVersion", "2.0");
    profile
      .withObject("configDefaults")
      .withObject("extractionOverrides")
      .putObject("resultParts")
      .put("testCode", "R.3.4")
      .put("assayName", "R.3.5")
      .put("assayVersion", "R.3.6")
      .put("analyte", "R.3.7")
      .put("complement", "R.3.8")
      .put("call", "R.4.1")
      .put("number", "R.4.2")
      .put("unit", "R.5")
      .put("range", "R.6")
      .put("flag", "R.7")
      .put("operator", "R.11")
      .put("instrument", "R.14");
    profile.withObject("configDefaults").putObject("numberFormat").put("decimalSeparator", ".");
    profile.set(
      "default_test_mappings",
      array(
        """
        [
          {
            "test_code":"HIVVL",
            "loinc":"20447-9",
            "unit":"copies/mL",
            "result_type":"quantitative",
            "values":["DETECTED","NOT DETECTED","INVALID"],
            "value_codes":{
              "DETECTED":[{"system":"http://loinc.org","code":"LA11882-0"},
                          {"system":"http://snomed.info/sct","code":"260373001"}],
              "NOT DETECTED":[{"system":"http://loinc.org","code":"LA11883-8"}],
              "INVALID":[{"system":"http://loinc.org","code":"LA15841-2"}]
            },
            "translations":{"DETECTED":["DÉTECTÉ"],"NOT DETECTED":["NON DÉTECTÉ"],"INVALID":["NON VALIDE"]},
            "call_component":"call",
            "components":[
              {"code":"call","label":"Call","result_type":"qualitative"},
              {"code":"LOG","label":"Log copies/mL","result_type":"quantitative","unit":"log copies/mL","sub_identity":"&LOG"},
              {"code":"HIV-1","label":"HIV-1","result_type":"qualitative","sub_identity":"HIV-1",
               "values":["POS","NEG"],
               "value_codes":{"POS":[{"system":"http://loinc.org","code":"LA6576-8"}],
                              "NEG":[{"system":"http://loinc.org","code":"LA6577-6"}]}}
            ],
            "assay":{"name":"Xpert HIV-1 Viral Load XC","version":"3"},
            "source":{"document":"Cepheid 303-0251 Rev. A","section":"1, 2.1.1"}
          },
          {
            "test_code":"RIF",
            "loinc":"89372-7",
            "result_type":"qualitative",
            "values":["DETECTED","NOT DETECTED","INDETERMINATE"],
            "value_codes":{"DETECTED":[{"system":"http://loinc.org","code":"LA11882-0"}]},
            "assay":{"name":"Xpert MTB/RIF Ultra","version":"1"},
            "source":{"document":"Cepheid 301-2002 Rev. E","section":"6.3.4.1.6"}
          }
        ]
        """
      )
    );
    return profile;
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
