package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class AnalyzerProfileValidator {

  private static final String SCHEMA_RESOURCE = "contracts/analyzer/v1/analyzer-profile.schema.json";

  /** The contract generation that bans site hints and requires the full result description. */
  private static final String BASELINE_SCHEMA_VERSION = "2.0";

  /** The standard code systems a value may cite: LOINC, SNOMED CT and CIEL. */
  private static final Set<String> STANDARD_CODE_SYSTEMS = Set.of(
    "http://loinc.org",
    "http://snomed.info/sct",
    "https://openconceptlab.org/orgs/CIEL/sources/CIEL"
  );

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
    List<String> failures = new ArrayList<>(
      schema.validate(profile).stream().map(ValidationMessage::getMessage).toList()
    );
    if (failures.isEmpty()) {
      validateConnectionFields(profile, failures);
      validateFilePattern(profile, failures);
      validateTabularResultSelection(profile, failures);
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
      validateValueSet(path, mapping, failures);
      validateRunFailureValues(path, mapping, failures);
      validateComponents(path, mapping, failures);
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

  /** The codes and translations a test or component gives its declared values. */
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
        for (JsonNode coding : entry.getValue()) {
          String system = coding.path("system").asText();
          if (!STANDARD_CODE_SYSTEMS.contains(system)) {
            failures.add(
              path +
              ".value_codes." +
              entry.getKey() +
              " cites a system that is not LOINC, SNOMED CT or CIEL: " +
              system
            );
          }
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
