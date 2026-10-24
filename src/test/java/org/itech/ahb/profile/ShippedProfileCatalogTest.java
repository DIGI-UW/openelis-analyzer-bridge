package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ShippedProfileCatalogTest {

  private static final Map<String, String> BASELINE_FINGERPRINTS = Map.of(
    "cepheid-genexpert-astm",
    "sha256:5d2ffee041a71c7d220fe5dda60f69c17a3fef357861fbfa83772c4f916eeca2",
    "hain-fluorocycler-xt",
    "sha256:8f28785af6c5af38859c2b393093eb74d6cc6e4e786b22c587cebfa9e2a2a1ba",
    "hain-fluorocycler-xt-madagascar",
    "sha256:8c0d62edb064ef081883104d17b666be734c8c2723c8da80a3d5ec953825b775",
    "mindray-bc5380",
    "sha256:540edc728f0320c4439f8e06265aca4a5f83116d778104689a7ec0eb297dd2dc",
    "thermo-quantstudio",
    "sha256:b8c148e55f0907494058e7eb56603e157d818d103645668f91b585e5e0bf49e0"
  );

  /** The systems a core-shipped profile cites; a distro's own profile may also cite its catalog's system. */
  private static final Set<String> STANDARD_SYSTEMS = Set.of(
    "http://loinc.org",
    "http://snomed.info/sct",
    "https://openconceptlab.org/orgs/CIEL/sources/CIEL"
  );

  @TempDir
  Path catalogDirectory;

  @Test
  void packagesAcceptedEstablishedProfilesThroughTheProductionCatalogPath() throws Exception {
    ProfileCatalogProperties properties = new ProfileCatalogProperties();
    Resource[] resources = new PathMatchingResourcePatternResolver().getResources(properties.getShippedPattern());

    assertThat(resources).isNotEmpty();

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    AnalyzerProfileValidator validator = new AnalyzerProfileValidator(objectMapper);
    // A shipped file is authored without fingerprints; the catalog derives them when it loads the file.
    // Tooling that checks a file against its schema before the Bridge loads it accepts it as written.
    assertSoftly(softly -> {
      for (Resource resource : resources) {
        try {
          ObjectNode profile = (ObjectNode) objectMapper.readTree(resource.getInputStream());
          String profileId = profile.path("profileMeta").path("id").asText();
          softly.assertThat(profile.path("catalog").has("recognitionFingerprint")).as(profileId).isFalse();
          softly.assertThat(profile.path("catalog").has("revisionFingerprint")).as(profileId).isFalse();
          softly.assertThat(validator.validationIssues(profile)).as(profileId + " as written").isEmpty();
        } catch (Exception exception) {
          throw new AssertionError("Cannot inspect " + resource.getDescription(), exception);
        }
      }
    });

    AnalyzerProfileCatalog catalog = new AnalyzerProfileCatalog(
      catalogDirectory,
      Arrays.stream(resources).toList(),
      objectMapper,
      Clock.systemUTC()
    );

    assertThat(catalog.latest())
      .extracting(revision -> revision.profile().path("profileMeta").path("id").asText())
      .containsExactly(
        "hain-fluorocycler-xt",
        "hain-fluorocycler-xt-madagascar",
        "cepheid-genexpert-astm",
        "mindray-bc5380",
        "thermo-quantstudio"
      );
    assertThat(catalog.latest()).allSatisfy(revision -> {
      ObjectNode profile = revision.profile();
      assertThat(profile.path("catalog").path("source").asText()).isEqualTo("SHIPPED");
      assertThat(profile.path("catalog").path("revision").asInt()).isPositive();
      assertThat(profile.path("configDefaults").has("qcRules")).isFalse();
      // A baseline profile cites its source for every test (rule 19).
      assertThat(profile.path("default_test_mappings")).allSatisfy(mapping -> {
        assertThat(mapping.path("source").path("document").asText())
          .as(mapping.path("test_code").asText())
          .isNotBlank();
        assertThat(mapping.path("source").path("section").asText()).as(mapping.path("test_code").asText()).isNotBlank();
      });
      assertThat(profile.path("configDefaults").path("dataFlow").asText()).isEqualTo("RESULTS_ONLY");

      var dataFlowField = StreamSupport.stream(profile.path("connectionFields").spliterator(), false)
        .filter(field -> "dataFlow".equals(field.path("key").asText()))
        .findFirst();
      assertThat(dataFlowField).isPresent();
      var choices = StreamSupport.stream(dataFlowField.orElseThrow().path("choices").spliterator(), false)
        .map(choice -> choice.path("value").asText())
        .toList();
      assertThat(choices).contains("RESULTS_ONLY");
      if (profile.path("capabilities").path("outboundOrders").asBoolean()) {
        assertThat(choices).contains("TWO_WAY");
      } else {
        assertThat(choices).doesNotContain("TWO_WAY");
      }

      switch (profile.path("profileMeta").path("id").asText()) {
        case "hain-fluorocycler-xt" -> assertThat(profile.path("result_value_order"))
          .extracting(JsonNode::asText)
          .containsExactly("result", "interpretation");
        case "cepheid-genexpert-astm" -> {
          JsonNode extraction = profile.path("configDefaults").path("extractionOverrides");
          JsonNode selection = extraction.path("resultRecordSelection");
          // Every record is a result; the profile's result parts say what each one is.
          assertThat(selection.path("mode").asText()).isEqualTo("ALL");
          assertThat(extraction.path("resultParts").isObject()).isTrue();
          // The cartridge's internal controls are warnings raised on FAIL, never parts of a test.
          assertThat(profile.path("default_test_mappings").findValuesAsText("sub_identity"))
            .noneMatch(identity -> identity.matches("(SPC|IQS-H|IQS-L)(&.*)?"));
          assertThat(profile.path("sample_flags").path("raisedValue").asText()).isEqualTo("FAIL");
          assertThat(profile.path("sample_flags").path("codes"))
            .extracting(JsonNode::asText)
            .contains("SPC", "SPC&Ct", "IQS-H", "IQS-L&EndPt");
        }
        case "hain-fluorocycler-xt-madagascar" -> {
          assertThat(profile.path("result_value_order"))
            .extracting(JsonNode::asText)
            .containsExactly("result", "interpretation");
          // The site workbook names the channel, not the test: 1 is the target, 2 the internal control.
          assertThat(profile.path("column_mapping").path("Col").asText()).isEqualTo("testCode");
          assertThat(profile.path("test_code_values").path("1").asText()).isEqualTo("VIH-1");
          assertThat(profile.path("test_code_values").path("2").asText()).isEqualTo("IC");
          // The internal control's validity is a warning about the sample, never a result.
          assertThat(profile.path("default_test_mappings"))
            .extracting(mapping -> mapping.path("test_code").asText())
            .containsExactly("VIH-1");
          assertThat(profile.path("sample_flags").path("raisedValue").asText()).isEqualTo("Invalid");
          assertThat(profile.path("sample_flags").path("names").path("IC").asText()).isEqualTo("Internal control invalid");
        }
        case "mindray-bc5380" -> {
          JsonNode extraction = profile.path("configDefaults").path("extractionOverrides");
          // The instrument's settings and histogram segments are not results; every other OBX is.
          assertThat(extraction.path("resultRecordSelection").path("mode").asText()).isEqualTo("EXCLUDE");
          assertThat(extraction.path("resultParts").isObject()).isTrue();
        }
        case "thermo-quantstudio" -> {
          assertThat(profile.path("result_value_order")).extracting(JsonNode::asText).containsExactly("result", "ctValue");
          // The IC row is a warning raised when its CT is Undetermined, never a result.
          assertThat(profile.path("default_test_mappings"))
            .extracting(mapping -> mapping.path("test_code").asText())
            .containsExactly("VIH-1");
          assertThat(profile.path("sample_flags").path("raisedValue").asText()).isEqualTo("Undetermined");
          assertThat(profile.path("sample_flags").path("names").path("IC").asText())
            .isEqualTo("Internal control not detected");
        }
        default -> throw new AssertionError("Unexpected priority profile");
      }
    });

    // Each baseline revision 1 carries the fingerprint the catalog computes for it.
    BASELINE_FINGERPRINTS.forEach((profileId, fingerprint) -> {
      ObjectNode baseline = catalog.require(profileId, 1).profile();
      assertThat(baseline.path("catalog").path("revisionFingerprint").asText()).as(profileId).isEqualTo(fingerprint);
    });
    // The baseline is revision 1 of every profile until the baseline ships.
    BASELINE_FINGERPRINTS.keySet().forEach(profileId ->
      assertThat(catalog.history(profileId)).as(profileId).hasSize(1)
    );
  }

  @Test
  void theShippedProfilesCiteOnlyStandardSystems() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Resource[] resources = new PathMatchingResourcePatternResolver()
      .getResources(new ProfileCatalogProperties().getShippedPattern());
    assertSoftly(softly -> {
      for (Resource resource : resources) {
        try {
          JsonNode tests = objectMapper.readTree(resource.getInputStream()).path("default_test_mappings");
          List<JsonNode> codings = new ArrayList<>();
          tests.findValues("codings").forEach(list -> list.forEach(codings::add));
          tests.findValues("value_codes").forEach(byValue -> byValue.forEach(list -> list.forEach(codings::add)));
          codings.forEach(coding ->
            softly.assertThat(STANDARD_SYSTEMS).as(resource.getFilename()).contains(coding.path("system").asText())
          );
        } catch (IOException exception) {
          throw new AssertionError("Cannot read " + resource.getDescription(), exception);
        }
      }
    });
  }
}
