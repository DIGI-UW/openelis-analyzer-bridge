package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ShippedProfileCatalogTest {

  private static final Map<String, String> BASELINE_FINGERPRINTS = Map.of(
    "cepheid-genexpert-astm",
    "sha256:1e2d53f6b797b25dbcd3c6947c5924ec1176b70b5dec2846fda691a6e74550ea",
    "hain-fluorocycler-xt",
    "sha256:5989781fabb39c7e405f6f1427823af5f9a40bfd0721abf470fda8f95551f4f7",
    "mindray-bc5380",
    "sha256:5abbfbb7b4e7772240ecb78bf5328a4736b7c8dab329e3d5fef868581fbfa5e6",
    "thermo-quantstudio",
    "sha256:9b5a84ddbc724ee696823898105a4f49edd41160fb9400bc51c109cda5d91ce1"
  );

  @TempDir
  Path catalogDirectory;

  @Test
  void packagesAcceptedEstablishedProfilesThroughTheProductionCatalogPath() throws Exception {
    ProfileCatalogProperties properties = new ProfileCatalogProperties();
    Resource[] resources = new PathMatchingResourcePatternResolver().getResources(properties.getShippedPattern());

    assertThat(resources).isNotEmpty();

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    ProfileFingerprintService fingerprints = new ProfileFingerprintService();
    assertSoftly(softly -> {
      for (Resource resource : resources) {
        try {
          ObjectNode profile = (ObjectNode) objectMapper.readTree(resource.getInputStream());
          String profileId = profile.path("profileMeta").path("id").asText();
          String actualRecognitionFingerprint = profile.path("catalog").path("recognitionFingerprint").asText();
          String actualRevisionFingerprint = profile.path("catalog").path("revisionFingerprint").asText();
          String recognitionFingerprint = fingerprints.recognitionFingerprint(profile.path("controlResultRecognition"));
          ((ObjectNode) profile.path("catalog")).put("recognitionFingerprint", recognitionFingerprint);
          String revisionFingerprint = fingerprints.revisionFingerprint(profile);

          softly
            .assertThat(actualRecognitionFingerprint)
            .as("%s recognition fingerprint", profileId)
            .isEqualTo(recognitionFingerprint);
          softly
            .assertThat(actualRevisionFingerprint)
            .as("%s revision fingerprint", profileId)
            .isEqualTo(revisionFingerprint);
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
      .containsExactly("hain-fluorocycler-xt", "cepheid-genexpert-astm", "mindray-bc5380", "thermo-quantstudio");
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
        }
        case "mindray-bc5380" -> {
          JsonNode extraction = profile.path("configDefaults").path("extractionOverrides");
          // The instrument's settings and histogram segments are not results; every other OBX is.
          assertThat(extraction.path("resultRecordSelection").path("mode").asText()).isEqualTo("EXCLUDE");
          assertThat(extraction.path("resultParts").isObject()).isTrue();
        }
        case "thermo-quantstudio" -> assertThat(profile.path("result_value_order"))
          .extracting(JsonNode::asText)
          .containsExactly("result", "ctValue");
        default -> throw new AssertionError("Unexpected priority profile");
      }
    });

    BASELINE_FINGERPRINTS.forEach((profileId, fingerprint) -> {
      ObjectNode baseline = catalog.requireLatest(profileId).profile();
      assertThat(baseline.path("catalog").path("revision").asInt()).as(profileId).isEqualTo(1);
      assertThat(baseline.path("catalog").path("revisionFingerprint").asText()).as(profileId).isEqualTo(fingerprint);
    });
  }
}
