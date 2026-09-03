package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AnalyzerProfileValidatorTest {

  private static final String FILE_PROFILE = "analyzer-profiles/fluorocycler-xt.json";

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
}
