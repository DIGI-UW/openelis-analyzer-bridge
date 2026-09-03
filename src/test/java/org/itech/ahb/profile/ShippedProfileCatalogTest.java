package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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

  private static final Map<String, String> REVISION_ONE_FINGERPRINTS = Map.of(
    "fluorocycler-xt",
    "sha256:8d099084227b7de083a6f8f0511234c8f09540534182a380060fe921a7f28c21",
    "genexpert-astm",
    "sha256:5184c52a44ec58932116fb3c4e9495b6cd8f05e4e84916f183f57b428a24e4ee",
    "quantstudio",
    "sha256:b940cb5cc7191a44570a87326e7e5c2054f4ac6df42cdf653ae113b9df143e6e"
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
          String recognitionFingerprint = fingerprints.recognitionFingerprint(
            profile.path("controlResultRecognition")
          );
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
      .containsExactly("fluorocycler-xt", "genexpert-astm", "quantstudio");
    assertThat(catalog.latest())
      .allSatisfy(revision -> {
        ObjectNode profile = revision.profile();
        assertThat(profile.path("catalog").path("source").asText()).isEqualTo("SHIPPED");
        assertThat(profile.path("catalog").path("revision").asInt()).isEqualTo(2);
        assertThat(profile.path("configDefaults").has("qcRules")).isFalse();
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
      });

    REVISION_ONE_FINGERPRINTS.forEach((profileId, fingerprint) -> {
      ObjectNode revisionOne = catalog.require(profileId, 1).profile();
      assertThat(revisionOne.path("catalog").path("revisionFingerprint").asText()).isEqualTo(fingerprint);
      assertThat(revisionOne.path("configDefaults").has("dataFlow")).isFalse();
      assertThat(
        StreamSupport
          .stream(revisionOne.path("connectionFields").spliterator(), false)
          .noneMatch(field -> "dataFlow".equals(field.path("key").asText()))
      )
        .isTrue();
    });
  }
}
