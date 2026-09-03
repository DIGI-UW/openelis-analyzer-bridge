package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AnalyzerProfileValidatorTest {

  private static final String FILE_PROFILE = "analyzer-profiles/fluorocycler-xt.json";
  private static final String ASTM_PROFILE = "analyzer-profiles/genexpert-astm.json";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AnalyzerProfileValidator validator = new AnalyzerProfileValidator(objectMapper);

  @Test
  void rejectsRegexShapedTextThatCannotMatchDeclaredFilesAsAGlob() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "(?i).*\\.(ods|xlsx|xls)$");

    assertThat(validator.validationIssues(profile))
      .contains(
        "$.configDefaults.filePattern does not match supported extension .ods",
        "$.configDefaults.filePattern does not match supported extension .xlsx",
        "$.configDefaults.filePattern does not match supported extension .xls"
      );
  }

  @Test
  void rejectsMalformedJavaGlobSyntax() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "*.[");

    assertThat(validator.validationIssues(profile))
      .contains("$.configDefaults.filePattern must be a Java NIO filename glob");
  }

  @Test
  void rejectsAGlobThatCannotMatchEveryDeclaredExtension() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("configDefaults")).put("filePattern", "*.xlsx");

    assertThat(validator.validationIssues(profile))
      .contains(
        "$.configDefaults.filePattern does not match supported extension .ods",
        "$.configDefaults.filePattern does not match supported extension .xls"
      );
  }

  @Test
  void rejectsDuplicatePrimaryAndAliasAnalyzerIdentities() throws Exception {
    ObjectNode duplicatePrimary = fileProfile();
    ObjectNode copiedMapping = duplicatePrimary
      .withArray("default_test_mappings")
      .path(0)
      .deepCopy();
    copiedMapping.put("loinc", "94500-6");
    duplicatePrimary.withArray("default_test_mappings").add(copiedMapping);

    assertThat(validator.validationIssues(duplicatePrimary))
      .contains("$.default_test_mappings contains duplicate analyzer identity VIH-1");

    ObjectNode duplicateAlias = fileProfile();
    ArrayNode aliases = duplicateAlias
      .withArray("default_test_mappings")
      .path(0)
      .withArray("aliases");
    aliases.add("VIH-1");

    assertThat(validator.validationIssues(duplicateAlias))
      .contains("$.default_test_mappings contains duplicate analyzer identity VIH-1");
  }

  @Test
  void rejectsMalformedSpecimenIdRecognitionPatterns() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode rule = profile
      .withObject("controlResultRecognition")
      .withObject("rules")
      .putObject("malformed-pattern");
    rule.put("ruleType", "SPECIMEN_ID_PATTERN");
    rule.put("operand", "^(QC-[)$");

    assertThat(validator.validationIssues(profile))
      .contains(
        "$.controlResultRecognition.rules.malformed-pattern.operand must be a valid Java regular expression"
      );
  }

  @Test
  void rejectsAResultValueOrderWithoutAMappedSourceColumn() throws Exception {
    ObjectNode profile = fileProfile();
    profile.withArray("result_value_order").removeAll().add("ctValue");

    assertThat(validator.validationIssues(profile))
      .contains("$.result_value_order selects ctValue but $.column_mapping has no matching source column");
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
    profile
      .withObject("configDefaults")
      .withObject("extractionOverrides")
      .remove("resultRecordSelection");

    assertThat(validator.validationIssues(profile))
      .anyMatch(issue -> issue.contains("resultRecordSelection"));
  }

  @Test
  void rejectsMalformedAstmResultRecordSelectionTargets() throws Exception {
    ObjectNode profile = astmProfile();
    profile
      .withObject("configDefaults")
      .withObject("extractionOverrides")
      .withObject("resultRecordSelection")
      .put("targetField", "R.0.5");

    assertThat(validator.validationIssues(profile))
      .anyMatch(issue -> issue.contains("targetField"));
  }

  @Test
  void rejectsDuplicateConnectionFieldKeys() throws Exception {
    ObjectNode profile = fileProfile();
    ArrayNode fields = (ArrayNode) profile.path("connectionFields");
    fields.add(fields.get(0).deepCopy());

    assertThat(validator.validationIssues(profile))
      .contains("$.connectionFields keys must be unique: directory");
  }

  @Test
  void rejectsConnectionVisibilityThatReferencesAnUndeclaredField() throws Exception {
    ObjectNode profile = fileProfile();
    ObjectNode condition = ((ObjectNode) profile.path("connectionFields").get(0)).putObject("visibleWhen");
    condition.put("fieldKey", "inventedTransport");
    condition.put("operator", "EQUALS");
    condition.put("value", "FILE");

    assertThat(validator.validationIssues(profile))
      .contains("$.connectionFields[directory].visibleWhen references undeclared field inventedTransport");
  }

  @Test
  void rejectsSecretDefaultsBecausePublishedProfilesMustNotContainCredentials() throws Exception {
    ObjectNode profile = fileProfile();
    ((ObjectNode) profile.path("connectionFields").get(1)).put("inputKind", "SECRET");

    assertThat(validator.validationIssues(profile))
      .contains("$.configDefaults.filePattern must not supply a SECRET default");
  }

  private ObjectNode fileProfile() throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(FILE_PROFILE)) {
      assertThat(input).as(FILE_PROFILE).isNotNull();
      return (ObjectNode) objectMapper.readTree(input);
    }
  }

  private ObjectNode astmProfile() throws Exception {
    return profile(ASTM_PROFILE);
  }

  private ObjectNode profile(String resource) throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      return (ObjectNode) objectMapper.readTree(input);
    }
  }
}
