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
import org.junit.jupiter.api.Assumptions;
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
    Path fixturePath = mockRoot.resolve(fixture.path("file").asText());

    Map<String, String> columnMappings = new LinkedHashMap<>();
    profile
      .path("column_mapping")
      .fields()
      .forEachRemaining(entry -> columnMappings.put(entry.getKey(), entry.getValue().asText()));

    Map<String, String> testCodeValues = new LinkedHashMap<>();
    profile
      .path("test_code_values")
      .fields()
      .forEachRemaining(entry -> testCodeValues.put(entry.getKey(), entry.getValue().asText()));
    Map<String, Set<String>> declaredValues = new LinkedHashMap<>();
    profile
      .path("default_test_mappings")
      .forEach(mapping -> declaredValues.put(mapping.path("test_code").asText(), textValues(mapping.path("values"))));

    Set<String> parsedCodes = new LinkedHashSet<>();
    List<String> unresolved = new java.util.ArrayList<>();
    int parsedResultCount;
    int flagRowCount;
    int raisedFlagCount;
    try (InputStream input = Files.newInputStream(fixturePath)) {
      var parsed = FileResultParser.parse(
        input,
        columnMappings,
        null,
        ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")),
        null,
        TabularResultValueSelection.fromProfile(profile),
        testCodeValues
      );
      int rowCount = parsed.stream().mapToInt(results -> results.results().size()).sum();
      SampleFlags flags = SampleFlags.fromProfile(profile);
      ResultReading reading = ResultReading.fromProfile(profile);
      parsed = parsed
        .stream()
        .map(results -> results.underProfileCodes(reading).withFileSampleFlags(flags).withTabularReading(reading))
        .toList();
      parsedResultCount = parsed.stream().mapToInt(results -> results.results().size()).sum();
      flagRowCount = rowCount - parsedResultCount;
      raisedFlagCount = parsed.stream().mapToInt(results -> results.sampleFlags().size()).sum();
      parsed.forEach(results ->
        results
          .results()
          .forEach(result -> {
            parsedCodes.add(result.testCode());
            Set<String> values = declaredValues.get(result.testCode());
            if (values == null) {
              unresolved.add(results.accessionNumber() + " " + result.testCode() + ": test not declared");
            } else if (result.parts().number() == null && !values.contains(result.parts().call())) {
              unresolved.add(results.accessionNumber() + " " + result.testCode() + ": value " + result.value());
            }
          }));
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
      softly.assertThat(parsedCodes).containsExactlyInAnyOrderElementsOf(mappingCodes(profile));
      softly.assertThat(unresolved).as("every row resolves to a declared test and value").isEmpty();
      softly.assertThat(parsedResultCount).as("one result row per sample").isEqualTo(2);
      softly.assertThat(flagRowCount).as("the internal-control row of each sample is a flag row").isEqualTo(2);
      softly.assertThat(raisedFlagCount).as("a valid internal control raises nothing").isZero();
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

  private static Set<String> textValues(JsonNode values) {
    Set<String> texts = new LinkedHashSet<>();
    values.forEach(value -> texts.add(value.asText()));
    return texts;
  }

  /** The mock checkout OpenELIS supplies when it runs the Bridge and the mock together. */
  private static Path requiredMockRoot() {
    String configured = System.getProperty("analyzerMockDir");
    Assumptions.assumeTrue(
      configured != null && !configured.isBlank(),
      "Needs the analyzer mock (-DanalyzerMockDir); OpenELIS runs it against both submodules"
    );
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
