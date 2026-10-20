package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AnalyzerProfileValidatorTest {

  private static final String FILE_PROFILE = "hain-fluorocycler-xt";
  private static final String ASTM_PROFILE = "cepheid-genexpert-astm";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AnalyzerProfileValidator validator = new AnalyzerProfileValidator(objectMapper);

  @Test
  void explicitEmptyRulesAreValidButAbsentRulesAndUnconfirmedNoneAreNot() throws Exception {
    ObjectNode profile = fileProfile();
    profile.putObject("controlResultRecognition").put("mode", "RULES").putObject("rules");
    assertThat(validator.validationIssues(profile)).isEmpty();
    profile.withObject("controlResultRecognition").remove("rules");
    assertThat(validator.validationIssues(profile)).isNotEmpty();
    profile.putObject("controlResultRecognition").put("mode", "NONE");
    assertThat(validator.validationIssues(profile)).isNotEmpty();
  }

  @Test
  void rejectsRegexShapedTextThatCannotMatchDeclaredFilesAsAGlob() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "(?i).*\\.(ods|xlsx|xls)$");

    assertThat(validator.validationIssues(profile)).contains(
      "$.configDefaults.filePattern does not match supported extension .ods",
      "$.configDefaults.filePattern does not match supported extension .xlsx",
      "$.configDefaults.filePattern does not match supported extension .xls"
    );
  }

  @Test
  void rejectsMalformedJavaGlobSyntax() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "*.[");

    assertThat(validator.validationIssues(profile)).contains(
      "$.configDefaults.filePattern must be a Java NIO filename glob"
    );
  }

  @Test
  void rejectsAGlobThatCannotMatchEveryDeclaredExtension() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "*.xlsx");

    assertThat(validator.validationIssues(profile)).contains(
      "$.configDefaults.filePattern does not match supported extension .ods",
      "$.configDefaults.filePattern does not match supported extension .xls"
    );
  }

  @Test
  void rejectsDuplicatePrimaryAndAliasAnalyzerIdentities() throws Exception {
    ObjectNode duplicatePrimary = fileProfile();
    ObjectNode copiedMapping = duplicatePrimary.withArray("default_test_mappings").path(0).deepCopy();
    copiedMapping.put("loinc", "94500-6");
    duplicatePrimary.withArray("default_test_mappings").add(copiedMapping);

    assertThat(validator.validationIssues(duplicatePrimary)).contains(
      "$.default_test_mappings contains duplicate analyzer identity VIH-1"
    );

    ObjectNode duplicateAlias = fileProfile();
    ArrayNode aliases = duplicateAlias.withArray("default_test_mappings").path(0).withArray("aliases");
    aliases.add("VIH-1");

    assertThat(validator.validationIssues(duplicateAlias)).contains(
      "$.default_test_mappings contains duplicate analyzer identity VIH-1"
    );
  }

  @Test
  void rejectsMalformedSpecimenIdRecognitionPatterns() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode rule = profile.withObject("controlResultRecognition").withObject("rules").putObject("malformed-pattern");
    rule.put("ruleType", "SPECIMEN_ID_PATTERN");
    rule.put("operand", "^(QC-[)$");

    assertThat(validator.validationIssues(profile)).contains(
      "$.controlResultRecognition.rules.malformed-pattern.operand must be a valid Java regular expression"
    );
  }

  @Test
  void rejectsAResultValueOrderWithoutAMappedSourceColumn() throws Exception {
    ObjectNode profile = fileProfile();
    profile.withArray("result_value_order").removeAll().add("ctValue");

    assertThat(validator.validationIssues(profile)).contains(
      "$.result_value_order selects ctValue but $.column_mapping has no matching source column"
    );
  }

  @Test
  void testCodeValuesNeedATestCodeColumn() throws Exception {
    ObjectNode profile = fileProfile();
    profile.withObject("column_mapping").remove("TargetName");
    profile.withObject("column_mapping").remove("testCode");
    profile.withObject("test_code_values").put("1", "VIH-1");

    assertThat(validator.validationIssues(profile)).contains(
      "$.test_code_values needs a column mapped to testCode in $.column_mapping"
    );
  }

  @Test
  void aCodeIsOfOneKindOnly() throws Exception {
    ObjectNode profile = astmProfile();
    String test = profile.path("default_test_mappings").get(0).path("test_code").asText();
    ObjectNode flags = profile.putObject("sample_flags");
    flags.put("raisedValue", "T");
    flags.putArray("codes").add(test);

    assertThat(validator.validationIssues(profile)).contains(
      "$.sample_flags.codes lists " + test + ", which $.default_test_mappings declares as a test"
    );
  }

  @Test
  void testCodeValuesNameADeclaredTestOrASampleFlag() throws Exception {
    ObjectNode profile = fileProfile();
    profile.withObject("test_code_values").put("2", "IC");
    String issue = "$.test_code_values.2 names IC, which neither $.default_test_mappings nor $.sample_flags declares";

    assertThat(validator.validationIssues(profile)).contains(issue);

    ObjectNode flags = profile.putObject("sample_flags");
    flags.put("raisedValue", "Invalid");
    flags.putArray("codes").add("IC");
    flags.putObject("names").put("IC", "Internal control invalid");

    assertThat(validator.validationIssues(profile)).doesNotContain(issue);
  }

  @Test
  void aFileProfileNamesEveryFlagBecauseAFileCarriesNoName() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode flags = profile.putObject("sample_flags");
    flags.put("raisedValue", "Invalid");
    flags.putArray("codes").add("IC");

    assertThat(validator.validationIssues(profile)).contains(
      "$.sample_flags.names must name IC: a file carries no name for a flag"
    );
  }

  @Test
  void aColumnOnlyFileProfileDefaultsToTheResultSemantic() throws Exception {
    ObjectNode profile = fileProfile();
    profile.remove("result_value_order");

    assertThat(validator.validationIssues(profile)).isEmpty();
  }

  @Test
  void requiresAnExplicitAstmResultRecordSelection() throws Exception {
    ObjectNode profile = astmProfile();
    profile.withObject("configDefaults").withObject("extractionOverrides").remove("resultRecordSelection");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("resultRecordSelection"));
  }

  @Test
  void rejectsMalformedAstmResultRecordSelectionTargets() throws Exception {
    ObjectNode profile = astmProfile();
    profile
      .withObject("configDefaults")
      .withObject("extractionOverrides")
      .withObject("resultRecordSelection")
      .put("targetField", "R.0.5");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("targetField"));
  }

  @Test
  void requiresCompleteSerialRuntimeSettings() throws Exception {
    ObjectNode profile = astmProfile();
    profile.withObject("transport_config").withObject("RS-232").remove("data_bits");

    assertThat(validator.validationIssues(profile)).anyMatch(issue -> issue.contains("data_bits"));
  }

  @Test
  void rejectsDuplicateConnectionFieldKeys() throws Exception {
    ObjectNode profile = fileProfile();
    ArrayNode fields = (ArrayNode) profile.path("connectionFields");
    fields.add(field(profile, "directory").deepCopy());

    assertThat(validator.validationIssues(profile)).contains("$.connectionFields keys must be unique: directory");
  }

  @Test
  void rejectsConnectionVisibilityThatReferencesAnUndeclaredField() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode condition = field(profile, "directory").putObject("visibleWhen");
    condition.put("fieldKey", "inventedTransport");
    condition.put("operator", "EQUALS");
    condition.put("value", "FILE");

    assertThat(validator.validationIssues(profile)).contains(
      "$.connectionFields[directory].visibleWhen references undeclared field inventedTransport"
    );
  }

  @Test
  void rejectsCircularConnectionVisibilityDependencies() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode directory = field(profile, "directory");
    ObjectNode filePattern = field(profile, "filePattern");
    directory.putObject("visibleWhen").put("fieldKey", "filePattern").put("operator", "EQUALS").put("value", "*.csv");
    filePattern
      .putObject("visibleWhen")
      .put("fieldKey", "directory")
      .put("operator", "EQUALS")
      .put("value", "/data/instruments");

    assertThat(validator.validationIssues(profile)).contains(
      "$.connectionFields.visibleWhen must not contain dependency cycles"
    );
  }

  @Test
  void rejectsSecretDefaultsBecausePublishedProfilesMustNotContainCredentials() throws Exception {
    ObjectNode profile = fileProfile();
    field(profile, "filePattern").put("inputKind", "SECRET");

    assertThat(validator.validationIssues(profile)).contains(
      "$.configDefaults.filePattern must not supply a SECRET default"
    );
  }

  @Test
  void aTestNamedOnlyByCodingsInAnySystemIsValid() throws Exception {
    ObjectNode profile = astmProfile();
    ObjectNode mapping = (ObjectNode) profile.withArray("default_test_mappings").path(0);
    mapping.remove("loinc");
    mapping.putArray("codings").addObject().put("system", "https://example.org/lab-concepts").put("code", "XPERT-VL");

    assertThat(validator.validationIssues(profile)).isEmpty();
  }

  @Test
  void aTestNamesItsTestByLoincOrByCodings() throws Exception {
    ObjectNode profile = astmProfile();
    ((ObjectNode) profile.withArray("default_test_mappings").path(0)).remove("loinc");

    assertThat(validator.validationIssues(profile)).isNotEmpty();
  }

  @Test
  void aTestNamesAtMostOneLoincCode() throws Exception {
    ObjectNode profile = astmProfile();
    ObjectNode mapping = (ObjectNode) profile.withArray("default_test_mappings").path(0);
    String code = mapping.path("test_code").asText();
    mapping.putArray("codings").addObject().put("system", "http://loinc.org").put("code", "94500-6");

    assertThat(validator.validationIssues(profile)).contains(
      "$.default_test_mappings." + code + " names more than one LOINC code"
    );

    mapping.withArray("codings").removeAll();
    mapping
      .withArray("codings")
      .addObject()
      .put("system", "http://loinc.org")
      .put("code", mapping.path("loinc").asText());
    assertThat(validator.validationIssues(profile)).isEmpty();
  }

  @Test
  void aLoincCodingIsALoincCode() throws Exception {
    ObjectNode profile = astmProfile();
    ObjectNode mapping = (ObjectNode) profile.withArray("default_test_mappings").path(0);
    String code = mapping.path("test_code").asText();
    mapping.remove("loinc");
    mapping.putArray("codings").addObject().put("system", "http://loinc.org").put("code", "HIV-VL");

    assertThat(validator.validationIssues(profile)).contains(
      "$.default_test_mappings." + code + ".codings cites a code that is not a LOINC code: HIV-VL"
    );
  }

  private static ObjectNode field(ObjectNode profile, String key) {
    for (var field : profile.path("connectionFields")) {
      if (key.equals(field.path("key").asText())) {
        return (ObjectNode) field;
      }
    }
    throw new AssertionError("no connection field " + key);
  }

  private ObjectNode fileProfile() throws Exception {
    return profile(FILE_PROFILE);
  }

  private ObjectNode astmProfile() throws Exception {
    return profile(ASTM_PROFILE);
  }

  private ObjectNode profile(String profileId) throws Exception {
    return PackagedProfiles.load(objectMapper, profileId);
  }
}
