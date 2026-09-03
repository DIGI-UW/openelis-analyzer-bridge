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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class AnalyzerProfileValidator {

  private static final String SCHEMA_RESOURCE = "contracts/analyzer/v1/analyzer-profile.schema.json";

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
    List<String> failures = new ArrayList<>(schema
      .validate(profile)
      .stream()
      .map(ValidationMessage::getMessage)
      .toList());
    if (failures.isEmpty()) {
      validateConnectionFields(profile, failures);
      validateFilePattern(profile, failures);
      validateTabularResultSelection(profile, failures);
      validateMappingIdentities(profile, failures);
      validateRecognitionPatterns(profile, failures);
    }
    failures.sort(Comparator.naturalOrder());
    return List.copyOf(failures);
  }

  private void validateConnectionFields(JsonNode profile, List<String> failures) {
    Set<String> fieldKeys = new LinkedHashSet<>();
    Set<String> duplicateKeys = new LinkedHashSet<>();
    for (JsonNode field : profile.path("connectionFields")) {
      String key = field.path("key").asText();
      if (!fieldKeys.add(key)) {
        duplicateKeys.add(key);
      }
      if (
        "SECRET".equals(field.path("inputKind").asText()) &&
        profile.path("configDefaults").has(key)
      ) {
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
  }

  private void validateTabularResultSelection(JsonNode profile, List<String> failures) {
    if (!"FILE".equals(profile.path("protocol").path("name").asText())) {
      return;
    }
    TabularResultValueSelection selection = TabularResultValueSelection.fromProfile(profile);
    Set<String> mappedSemanticFields = new HashSet<>();
    profile
      .path("column_mapping")
      .elements()
      .forEachRemaining(value -> mappedSemanticFields.add(value.asText()));
    selection.semanticFields().forEach(field -> {
      if (!mappedSemanticFields.contains(field)) {
        failures.add(
          "$.result_value_order selects " +
          field +
          " but $.column_mapping has no matching source column"
        );
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
      for (JsonNode alias : mapping.path("aliases")) {
        validateMappingIdentity(alias.asText(), identities, failures);
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
            "$.controlResultRecognition.rules." +
            entry.getKey() +
            ".operand must be a valid Java regular expression"
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
