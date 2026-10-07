package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.itech.ahb.fhir.FileResultParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class PriorityProfileMockFixtureTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path temporaryDirectory;

  @Test
  void publishedFluoroCyclerProfileMatchesAndParsesTheMockFileFixture() throws Exception {
    Path mockRoot = requiredMockRoot();
    JsonNode template = JSON.readTree(mockRoot.resolve("templates/hain_fluorocycler.json").toFile());
    JsonNode profile = profile(template.path("profileRef"));
    JsonNode resolvedTemplate = resolveMockTemplate(mockRoot, "hain_fluorocycler", profile);
    JsonNode fixture = template.path("fixture");
    String profileFileTestCode = onlyMappingCode(profile);
    Path fixturePath = mockRoot.resolve(fixture.path("file").asText());

    Map<String, String> columnMappings = new LinkedHashMap<>();
    profile
      .path("column_mapping")
      .fields()
      .forEachRemaining(entry -> columnMappings.put(entry.getKey(), entry.getValue().asText()));

    Set<String> parsedCodes = new LinkedHashSet<>();
    int parsedResultCount;
    try (InputStream input = Files.newInputStream(fixturePath)) {
      var parsed = FileResultParser.parse(
        input,
        columnMappings,
        profileFileTestCode,
        ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")),
        null,
        TabularResultValueSelection.fromProfile(profile)
      );
      parsedResultCount = parsed.stream().mapToInt(results -> results.results().size()).sum();
      parsed.forEach(results -> results.results().forEach(result -> parsedCodes.add(result.testCode())));
    }

    assertSoftly(softly -> {
      softly.assertThat(fixturePath).isRegularFile();
      softly
        .assertThat(template.path("profileRef").path("profileId").asText())
        .isEqualTo(profile.path("profileMeta").path("id").asText());
      softly
        .assertThat(template.path("profileRef").path("revision").asInt())
        .isEqualTo(profile.path("catalog").path("revision").asInt());
      softly.assertThat(template.has("profile")).isFalse();
      softly.assertThat(fixture.has("perFileTestCode")).isFalse();
      softly
        .assertThat(profile.path("protocol").path("name").asText())
        .isEqualTo(resolvedTemplate.path("protocol").path("type").asText());
      softly
        .assertThat(profile.path("configDefaults").path("fileFormat").asText())
        .isEqualTo(fixture.path("format").asText());
      softly
        .assertThat(
          FileSystems.getDefault()
            .getPathMatcher("glob:" + profile.path("configDefaults").path("filePattern").asText())
            .matches(fixturePath.getFileName())
        )
        .as("Bridge watcher glob matches the mock fixture")
        .isTrue();
      softly.assertThat(mappingCodes(profile)).containsExactly(profileFileTestCode);
      softly.assertThat(parsedCodes).containsExactly(profileFileTestCode);
      softly.assertThat(parsedResultCount).as("every mock FILE result row is preserved").isEqualTo(4);
    });
  }

  private JsonNode resolveMockTemplate(Path mockRoot, String templateName, JsonNode profile) throws Exception {
    Path profileRoot = temporaryDirectory.resolve("profiles-" + templateName);
    Files.createDirectories(profileRoot);
    JSON.writeValue(profileRoot.resolve(profile.path("profileMeta").path("id").asText() + ".json").toFile(), profile);

    String script =
      """
      import json
      import sys
      sys.path.insert(0, sys.argv[1])
      from profile_adapter import load_profile_backed_template
      with open(sys.argv[3], encoding="utf-8") as source:
          template = json.load(source)
      print(json.dumps(load_profile_backed_template(sys.argv[2], template)))
      """;
    ProcessBuilder processBuilder = new ProcessBuilder(
      "python3",
      "-c",
      script,
      mockRoot.toString(),
      templateName,
      mockRoot.resolve("templates/" + templateName + ".json").toString()
    );
    processBuilder.directory(mockRoot.toFile());
    processBuilder.environment().put("ANALYZER_BRIDGE_PROFILES_DIR", profileRoot.toString());
    Process process = processBuilder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    assertThat(exitCode).as(error).isZero();
    return JSON.readTree(output);
  }

  private JsonNode profile(JsonNode profileRef) throws Exception {
    String profileId = profileRef.path("profileId").asText();
    int revision = profileRef.path("revision").asInt();
    ProfileCatalogProperties properties = new ProfileCatalogProperties();
    Resource[] resources = new PathMatchingResourcePatternResolver().getResources(properties.getShippedPattern());
    AnalyzerProfileCatalog catalog = new AnalyzerProfileCatalog(
      temporaryDirectory.resolve("catalog-" + profileId),
      Arrays.stream(resources).toList(),
      JSON,
      Clock.systemUTC()
    );
    return catalog.require(profileId, revision).profile();
  }

  private static Set<String> mappingCodes(JsonNode profile) {
    Set<String> codes = new LinkedHashSet<>();
    profile.path("default_test_mappings").forEach(mapping -> codes.add(mapping.path("test_code").asText()));
    return codes;
  }

  private static String onlyMappingCode(JsonNode profile) {
    Set<String> codes = mappingCodes(profile);
    assertThat(codes).hasSize(1);
    return codes.iterator().next();
  }

  private static Set<String> textValues(JsonNode values) {
    Set<String> texts = new LinkedHashSet<>();
    values.forEach(value -> texts.add(value.asText()));
    return texts;
  }

  private static Path requiredMockRoot() {
    String configured = System.getProperty("analyzerMockDir");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("Run with -DanalyzerMockDir=/path/to/analyzer-mock-server");
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
