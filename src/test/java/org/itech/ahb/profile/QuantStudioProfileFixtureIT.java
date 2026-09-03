package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.TabularFileLayout;
import org.junit.jupiter.api.Test;

class QuantStudioProfileFixtureIT {

  private static final String PROFILE_RESOURCE = "analyzer-profiles/quantstudio.json";

  @Test
  void publishedProfileParsesTheMockQs5AndQs7Fixtures() throws Exception {
    Path mockRoot = requiredMockRoot();
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode profile;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(PROFILE_RESOURCE)) {
      assertThat(input).as(PROFILE_RESOURCE).isNotNull();
      profile = objectMapper.readTree(input);
    }

    Map<String, String> columnMappings = new LinkedHashMap<>();
    profile.path("column_mapping").fields().forEachRemaining(entry ->
      columnMappings.put(entry.getKey(), entry.getValue().asText())
    );
    JsonNode detection = profile.path("sheet_detection");
    TabularFileLayout layout = TabularFileLayout.headerScan(
      objectMapper.convertValue(detection.path("preferred_sheet_names"), objectMapper.getTypeFactory()
        .constructCollectionType(List.class, String.class)),
      detection.path("header_marker").asText(),
      detection.path("max_sheets_to_scan").asInt(),
      detection.path("max_rows_to_scan").asInt()
    );

    Set<String> declaredCodes = new LinkedHashSet<>();
    for (JsonNode mapping : profile.path("default_test_mappings")) {
      declaredCodes.add(mapping.path("test_code").asText());
      mapping.path("aliases").forEach(alias -> declaredCodes.add(alias.asText()));
    }

    Set<String> observedAcrossFixtures = new LinkedHashSet<>();

    for (String templateName : List.of("quantstudio5", "quantstudio7")) {
      JsonNode template = objectMapper.readTree(mockRoot.resolve("templates/" + templateName + ".json").toFile());
      Path fixturePath = mockRoot.resolve(template.path("fixture").path("file").asText());
      assertThat(fixturePath).isRegularFile();
      assertThat(template.path("profileRef").path("profileId").asText())
        .as(templateName + " profile")
        .isEqualTo(profile.path("profileMeta").path("id").asText());
      assertThat(template.path("profileRef").path("revision").asInt())
        .as(templateName + " profile revision")
        .isEqualTo(profile.path("catalog").path("revision").asInt());
      assertThat(template.has("profile")).as(templateName + " unversioned profile reference").isFalse();
      assertThat(
        FileSystems.getDefault()
          .getPathMatcher("glob:" + profile.path("configDefaults").path("filePattern").asText())
          .matches(fixturePath.getFileName())
      )
        .as(templateName + " Bridge watcher glob")
        .isTrue();
      try (InputStream input = Files.newInputStream(fixturePath)) {
        var parsed = FileResultParser.parse(
          input,
          columnMappings,
          null,
          List.of(),
          List.of(),
          layout
        );
        assertThat(parsed).as(fixturePath.toString()).isNotEmpty();
        Set<String> observedCodes = new LinkedHashSet<>();
        parsed.forEach(results ->
          results.results().forEach(result -> observedCodes.add(result.testCode()))
        );
        observedAcrossFixtures.addAll(observedCodes);
        assertThat(observedCodes).as(fixturePath.toString()).isSubsetOf(declaredCodes);
      }
    }
    assertThat(observedAcrossFixtures).containsExactlyInAnyOrderElementsOf(declaredCodes);
  }

  private static Path requiredMockRoot() {
    String configured = System.getProperty("analyzerMockDir");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("Run with -DanalyzerMockDir=/path/to/analyzer-mock-server");
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
