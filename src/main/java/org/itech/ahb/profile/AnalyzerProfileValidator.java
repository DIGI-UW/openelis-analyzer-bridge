package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class AnalyzerProfileValidator {

  private static final String SCHEMA_RESOURCE = "contracts/analyzer/v1/analyzer-profile.schema.json";

  /** The contract generation that bans site hints and requires the full result description. */
  private static final String BASELINE_SCHEMA_VERSION = "2.0";

  private static final Pattern LOINC_CODE = Pattern.compile("^[0-9]{1,8}-[0-9]$");

  private final JsonSchema schema;

  AnalyzerProfileValidator(ObjectMapper objectMapper) {
    try (InputStream input = AnalyzerProfileValidator.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new ProfileCatalogException("Analyzer profile schema is not available: " + SCHEMA_RESOURCE);
      }
      schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(objectMapper.readTree(input));
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot load analyzer profile schema", exception);
    }
  }

  void validate(JsonNode profile) {
    List<String> failures = validationIssues(profile);
    if (!failures.isEmpty()) {
      throw new ProfileCatalogException(
        "Analyzer profile violates the published schema: " + String.join("; ", failures)
      );
    }
  }

  List<String> validationIssues(JsonNode profile) {
    return issues(profile, ValidationMessage::getMessage);
  }

  /** The issues a draft shows its author: a part not saved yet names the step that saves it. */
  List<String> draftIssues(JsonNode profile) {
    return issues(profile, AnalyzerProfileValidator::draftIssue);
  }

  private static String draftIssue(ValidationMessage message) {
    if (!"required".equals(message.getType())) {
      return message.getMessage();
    }
    JsonNodePath location = message.getInstanceLocation();
    String part = location.getNameCount() == 0 ? message.getProperty() : location.getName(0);
    return "controlResultRecognition".equals(part)
      ? "Save control recognition before publishing."
      : "Save the profile settings before publishing.";
  }

  private List<String> issues(JsonNode profile, Function<ValidationMessage, String> wording) {
    List<String> failures = new ArrayList<>(schema.validate(profile).stream().map(wording).distinct().toList());
    if (failures.isEmpty()) {
      validateConnectionFields(profile, failures);
      validateFilePattern(profile, failures);
      validateTabularResultSelection(profile, failures);
      validateTestCodeValues(profile, failures);
      validateRecordKinds(profile, failures);
      validateMappingIdentities(profile, failures);
      validateResultDescriptions(profile, failures);
      validateRecognitionPatterns(profile, failures);
    }
    failures.sort(Comparator.naturalOrder());
    return List.copyOf(failures);
  }

  private void validateConnectionFields(JsonNode profile, List<String> failures) {
    Set<String> fieldKeys = new LinkedHashSet<>();
    Set<String> duplicateKeys = new LinkedHashSet<>();
    Map<String, JsonNode> fieldsByKey = new LinkedHashMap<>();
    for (JsonNode field : profile.path("connectionFields")) {
      String key = field.path("key").asText();
      if (!fieldKeys.add(key)) {
        duplicateKeys.add(key);
      }
      fieldsByKey.putIfAbsent(key, field);
      if ("SECRET".equals(field.path("inputKind").asText()) && profile.path("configDefaults").has(key)) {
        failures.add("$.configDefaults." + key + " must not supply a SECRET default");
      }
    }
    duplicateKeys.forEach(key -> failures.add("$.connectionFields keys must be unique: " + key));

    for (JsonNode field : profile.path("connectionFields")) {
      JsonNode condition = field.path("visibleWhen");
      if (condition.isObject() && !fieldKeys.contains(condition.path("fieldKey").asText())) {
        failures.add(
          "$.connectionFields[" +
          field.path("key").asText() +
          "].visibleWhen references undeclared field " +
          condition.path("fieldKey").asText()
        );
      }
    }
    if (hasVisibilityDependencyCycle(fieldsByKey)) {
      failures.add("$.connectionFields.visibleWhen must not contain dependency cycles");
    }
  }

  private static boolean hasVisibilityDependencyCycle(Map<String, JsonNode> fieldsByKey) {
    Set<String> complete = new HashSet<>();
    for (String key : fieldsByKey.keySet()) {
      if (hasVisibilityDependencyCycle(key, fieldsByKey, new HashSet<>(), complete)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasVisibilityDependencyCycle(
    String key,
    Map<String, JsonNode> fieldsByKey,
    Set<String> visiting,
    Set<String> complete
  ) {
    if (complete.contains(key)) {
      return false;
    }
    if (!visiting.add(key)) {
      return true;
    }
    JsonNode field = fieldsByKey.get(key);
    JsonNode condition = field == null ? null : field.path("visibleWhen");
    String dependency = condition != null && condition.isObject() ? condition.path("fieldKey").asText() : null;
    if (
      dependency != null &&
      fieldsByKey.containsKey(dependency) &&
      hasVisibilityDependencyCycle(dependency, fieldsByKey, visiting, complete)
    ) {
      return true;
    }
    visiting.remove(key);
    complete.add(key);
    return false;
  }

  private void validateTabularResultSelection(JsonNode profile, List<String> failures) {
    if (!"FILE".equals(profile.path("protocol").path("name").asText())) {
      return;
    }
    TabularResultValueSelection selection = TabularResultValueSelection.fromProfile(profile);
    Set<String> mappedSemanticFields = new HashSet<>();
    profile.path("column_mapping").elements().forEachRemaining(value -> mappedSemanticFields.add(value.asText()));
    selection
      .semanticFields()
      .forEach(field -> {
        if (!mappedSemanticFields.contains(field)) {
          failures.add("$.result_value_order selects " + field + " but $.column_mapping has no matching source column");
        }
      });
  }

  /** A FILE column value the profile translates must name a declared test or a sample flag. */
  private void validateTestCodeValues(JsonNode profile, List<String> failures) {
    JsonNode values = profile.path("test_code_values");
    if (!"FILE".equals(profile.path("protocol").path("name").asText()) || values.isMissingNode()) {
      return;
    }
    boolean testCodeColumn = false;
    for (JsonNode meaning : profile.path("column_mapping")) {
      testCodeColumn |= "testCode".equals(meaning.asText());
    }
    if (!testCodeColumn) {
      failures.add("$.test_code_values needs a column mapped to testCode in $.column_mapping");
    }
    Set<String> declared = new HashSet<>();
    profile.path("default_test_mappings").forEach(mapping -> declared.add(mapping.path("test_code").asText()));
    profile.path("sample_flags").path("codes").forEach(code -> declared.add(code.asText()));
    values
      .fields()
      .forEachRemaining(entry -> {
        if (!declared.contains(entry.getValue().asText())) {
          failures.add(
            "$.test_code_values." +
            entry.getKey() +
            " names " +
            entry.getValue().asText() +
            ", which neither $.default_test_mappings nor $.sample_flags declares"
          );
        }
      });
  }

  /**
   * What an instrument sends is of one kind each: a result the profile declares as a test, a
   * warning about the sample ($.sample_flags), or a record it does not receive
   * (resultRecordSelection EXCLUDE). No code may be two of these.
   */
  private void validateRecordKinds(JsonNode profile, List<String> failures) {
    Set<String> tests = new LinkedHashSet<>();
    profile.path("default_test_mappings").forEach(mapping -> tests.add(mapping.path("test_code").asText()));
    JsonNode flags = profile.path("sample_flags");
    Set<String> flagCodes = new LinkedHashSet<>();
    flags.path("codes").forEach(code -> flagCodes.add(code.asText()));
    Set<String> excluded = new LinkedHashSet<>();
    JsonNode selection = profile.path("configDefaults").path("extractionOverrides").path("resultRecordSelection");
    if ("EXCLUDE".equals(selection.path("mode").asText())) {
      selection.path("values").forEach(value -> excluded.add(value.asText()));
    }
    for (String code : flagCodes) {
      if (tests.contains(code)) {
        failures.add("$.sample_flags.codes lists " + code + ", which $.default_test_mappings declares as a test");
      }
      if (excluded.contains(code)) {
        failures.add("$.sample_flags.codes lists " + code + ", which resultRecordSelection does not receive");
      }
    }
    for (String code : tests) {
      if (excluded.contains(code)) {
        failures.add("$.default_test_mappings declares " + code + ", which resultRecordSelection does not receive");
      }
    }
    if (flags.isObject()) {
      for (JsonNode name : flags.path("names")) {
        if (name.asText().isBlank()) {
          failures.add("$.sample_flags.names gives a blank name");
        }
      }
      flags
        .path("names")
        .fieldNames()
        .forEachRemaining(code -> {
          if (!flagCodes.contains(code)) {
            failures.add("$.sample_flags.names names " + code + ", which $.sample_flags.codes does not list");
          }
        });
      if ("FILE".equals(profile.path("protocol").path("name").asText())) {
        for (String code : flagCodes) {
          if (!flags.path("names").has(code)) {
            failures.add("$.sample_flags.names must name " + code + ": a file carries no name for a flag");
          }
        }
      }
    }
  }

  private void validateFilePattern(JsonNode profile, List<String> failures) {
    if (!"FILE".equals(profile.path("protocol").path("name").asText())) {
      return;
    }

    PathMatcher matcher;
    try {
      matcher = FileSystems.getDefault()
        .getPathMatcher("glob:" + profile.path("configDefaults").path("filePattern").asText());
    } catch (IllegalArgumentException exception) {
      failures.add("$.configDefaults.filePattern must be a Java NIO filename glob");
      return;
    }

    for (JsonNode extension : profile.path("supported_extensions")) {
      String value = extension.asText();
      if (!matcher.matches(Path.of("result" + value).getFileName())) {
        failures.add("$.configDefaults.filePattern does not match supported extension " + value);
      }
    }
  }

  private void validateMappingIdentities(JsonNode profile, List<String> failures) {
    Set<String> identities = new HashSet<>();
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      validateMappingIdentity(mapping.path("test_code").asText(), identities, failures);
      Set<String> values = new HashSet<>();
      mapping.path("values").forEach(value -> values.add(value.asText()));
      mapping
        .path("result_value_hints")
        .fieldNames()
        .forEachRemaining(raw -> {
          if (!values.contains(raw)) {
            failures.add("$.default_test_mappings.result_value_hints must name a declared raw value: " + raw);
          }
        });
      for (JsonNode alias : mapping.path("aliases")) {
        validateMappingIdentity(alias.asText(), identities, failures);
      }
    }
  }

  private void validateResultDescriptions(JsonNode profile, List<String> failures) {
    boolean baseline = BASELINE_SCHEMA_VERSION.equals(profile.path("schemaVersion").asText());
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      String path = "$.default_test_mappings." + mapping.path("test_code").asText();
      if (baseline) {
        for (String hint : List.of("specimen_type_hint", "result_value_hints")) {
          if (mapping.has(hint)) {
            failures.add(path + " must not carry " + hint + ": a profile describes the instrument, not a site");
          }
        }
        if (!mapping.has("result_type")) {
          failures.add(path + ".result_type is required");
        }
        if (
          "quantitative".equals(mapping.path("result_type").asText()) &&
          mapping.has("values") &&
          !mapping.has("call_component")
        ) {
          failures.add(path + " declares values on a quantitative test, so it needs a call_component");
        }
      }
      validateTestCodings(path, mapping, failures);
      validateValueSet(path, mapping, failures);
      validateRunFailureValues(path, mapping, failures);
      validateComponents(path, mapping, failures);
    }
  }

  /** A test names one LOINC code at most, whether as {@code loinc} or among its codings. */
  private void validateTestCodings(String path, JsonNode mapping, List<String> failures) {
    Set<String> loincCodes = TestCodings.loincCodes(mapping);
    if (loincCodes.size() > 1) {
      failures.add(path + " names more than one LOINC code");
    }
    for (String code : loincCodes) {
      if (!LOINC_CODE.matcher(code).matches()) {
        failures.add(path + ".codings cites a code that is not a LOINC code: " + code);
      }
    }
  }

  /** A value that says the run produced nothing is not an answer a lab could map. */
  private void validateRunFailureValues(String path, JsonNode mapping, List<String> failures) {
    Set<String> values = new HashSet<>();
    mapping.path("values").forEach(value -> values.add(value.asText()));
    mapping
      .path("run_failure_values")
      .forEach(value -> {
        if (values.contains(value.asText())) {
          failures.add(path + ".run_failure_values repeats a declared value: " + value.asText());
        }
      });
  }

  /** The codes, translations and defaults a test or component gives its declared values. */
  private void validateValueSet(String path, JsonNode owner, List<String> failures) {
    Set<String> values = new LinkedHashSet<>();
    owner.path("values").forEach(value -> values.add(value.asText()));
    Set<String> translatable = new LinkedHashSet<>(values);
    owner.path("run_failure_values").forEach(value -> translatable.add(value.asText()));
    owner
      .path("value_codes")
      .fields()
      .forEachRemaining(entry -> {
        if (!values.contains(entry.getKey())) {
          failures.add(path + ".value_codes names an undeclared value: " + entry.getKey());
        }
      });
    owner
      .path("value_defaults")
      .fieldNames()
      .forEachRemaining(value -> {
        if (!values.contains(value)) {
          failures.add(path + ".value_defaults names an undeclared value: " + value);
        }
      });
    Map<String, String> textOwners = new LinkedHashMap<>();
    translatable.forEach(value -> textOwners.put(value, value));
    owner
      .path("translations")
      .fields()
      .forEachRemaining(entry -> {
        if (!translatable.contains(entry.getKey())) {
          failures.add(path + ".translations names an undeclared value: " + entry.getKey());
          return;
        }
        for (JsonNode translation : entry.getValue()) {
          String previous = textOwners.putIfAbsent(translation.asText(), entry.getKey());
          if (previous != null && !previous.equals(entry.getKey())) {
            failures.add(path + ".translations gives one text to two values: " + translation.asText());
          }
        }
      });
  }

  private void validateComponents(String path, JsonNode mapping, List<String> failures) {
    Set<String> codes = new HashSet<>();
    Set<String> subIdentities = new HashSet<>();
    Set<String> repeatedCodes = new LinkedHashSet<>();
    Set<String> repeatedSubIdentities = new LinkedHashSet<>();
    Map<String, JsonNode> componentsByCode = new LinkedHashMap<>();
    for (JsonNode component : mapping.path("components")) {
      String code = component.path("code").asText();
      if (!codes.add(code)) {
        repeatedCodes.add(code);
      }
      componentsByCode.putIfAbsent(code, component);
      if (component.has("sub_identity") && !subIdentities.add(component.path("sub_identity").asText())) {
        repeatedSubIdentities.add(component.path("sub_identity").asText());
      }
      String componentPath = path + ".components." + code;
      validateValueSet(componentPath, component, failures);
      validateRunFailureValues(componentPath, component, failures);
      if (
        "qualitative".equals(component.path("result_type").asText()) &&
        component.has("sub_identity") &&
        !component.has("values")
      ) {
        failures.add(componentPath + " is qualitative and declares no values");
      }
    }
    repeatedCodes.forEach(code -> failures.add(path + ".components repeats the code " + code));
    repeatedSubIdentities.forEach(
      subIdentity -> failures.add(path + ".components repeats the sub_identity " + subIdentity)
    );
    if (mapping.has("call_component")) {
      String callComponent = mapping.path("call_component").asText();
      JsonNode named = componentsByCode.get(callComponent);
      if (named == null) {
        failures.add(path + ".call_component names no declared component: " + callComponent);
      } else if (named.has("sub_identity")) {
        failures.add(path + ".call_component must name the component with no sub_identity: " + callComponent);
      }
    }
  }

  private void validateRecognitionPatterns(JsonNode profile, List<String> failures) {
    profile
      .path("controlResultRecognition")
      .path("rules")
      .fields()
      .forEachRemaining(entry -> {
        JsonNode rule = entry.getValue();
        if (!"SPECIMEN_ID_PATTERN".equals(rule.path("ruleType").asText())) {
          return;
        }
        try {
          Pattern.compile(rule.path("operand").asText());
        } catch (PatternSyntaxException exception) {
          failures.add(
            "$.controlResultRecognition.rules." + entry.getKey() + ".operand must be a valid Java regular expression"
          );
        }
      });
  }

  private void validateMappingIdentity(String identity, Set<String> identities, List<String> failures) {
    if (!identities.add(identity)) {
      failures.add("$.default_test_mappings contains duplicate analyzer identity " + identity);
    }
  }
}
