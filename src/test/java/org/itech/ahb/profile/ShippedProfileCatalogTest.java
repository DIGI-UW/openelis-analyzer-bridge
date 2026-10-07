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

  private static final Map<Integer, String> GENEXPERT_PUBLISHED_FINGERPRINTS = Map.of(
    1,
    "sha256:57fb4fdee4db022236defb64a9fd5822b7714687031a895861f018b4875eafbb",
    2,
    "sha256:30f66fae19909799e88cc57b104fa72e6f6bfdf53016ccb81ea5573a8bdb8e90",
    3,
    "sha256:8ce4aea990149d459811fd735c6c5a090a0960f48742b2a0b6316b89e31548a7",
    4,
    "sha256:26f400271c3ecd14dd5ddf0e7b47a36995db7e740e6abf006aebb003b7815ba0",
    5,
    "sha256:3ea1cb123338481ee499a90168583d58369b8d3a6b7520b368324aa5bb8bc314",
    6,
    "sha256:72e4d3ca7ebe5715c750a19579e038178709722294ee78686bf0f751f9253001",
    7,
    "sha256:55c93b9f8327a26ccf7e04db1e1e08e8ce0c9214a2594351625247c22100bc84",
    8,
    "sha256:47d8857b028c36ac65959e33100f9db7afe23fac3841bd96543174e6b845f2be"
  );

  private static final Map<String, String> REVISION_ONE_FINGERPRINTS = Map.of(
    "fluorocycler-xt",
    "sha256:8d099084227b7de083a6f8f0511234c8f09540534182a380060fe921a7f28c21",
    "genexpert-astm",
    "sha256:57fb4fdee4db022236defb64a9fd5822b7714687031a895861f018b4875eafbb",
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
      .containsExactly("fluorocycler-xt", "genexpert-astm", "quantstudio");
    assertThat(catalog.latest()).allSatisfy(revision -> {
      ObjectNode profile = revision.profile();
      assertThat(profile.path("catalog").path("source").asText()).isEqualTo("SHIPPED");
      assertThat(profile.path("catalog").path("revision").asInt()).isPositive();
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

      switch (profile.path("profileMeta").path("id").asText()) {
        case "fluorocycler-xt" -> assertThat(profile.path("result_value_order"))
          .extracting(JsonNode::asText)
          .containsExactly("result", "interpretation");
        case "genexpert-astm" -> {
          JsonNode extraction = profile.path("configDefaults").path("extractionOverrides");
          JsonNode selection = extraction.path("resultRecordSelection");
          if ("2.0".equals(profile.path("schemaVersion").asText())) {
            // Every record is a result; the profile's result parts say what each one is.
            assertThat(selection.path("mode").asText()).isEqualTo("ALL");
            assertThat(extraction.path("resultParts").isObject()).isTrue();
          } else {
            assertThat(selection.path("mode").asText()).isEqualTo("FIELD_NON_BLANK");
            assertThat(selection.path("targetField").asText()).isEqualTo("R.3.5");
          }
        }
        case "quantstudio" -> assertThat(profile.path("result_value_order"))
          .extracting(JsonNode::asText)
          .containsExactly("result", "ctValue");
        default -> throw new AssertionError("Unexpected priority profile");
      }
    });

    // Connections pin a revision by fingerprint: a published revision can never change, only be
    // followed by a new one. Revision 5 added host for SERVER connections and senderId.
    GENEXPERT_PUBLISHED_FINGERPRINTS.forEach(
      (revision, fingerprint) ->
        assertThat(
          catalog.require("genexpert-astm", revision).profile().path("catalog").path("revisionFingerprint").asText()
        )
          .as("genexpert-astm revision %d", revision)
          .isEqualTo(fingerprint)
    );
    assertThat(catalog.requireLatest("genexpert-astm").profile().path("catalog").path("revision").asInt()).isEqualTo(8);

    assertThat(catalog.require("genexpert-astm", 7).profile().path("default_test_mappings")).anySatisfy(mapping -> {
      assertThat(mapping.path("test_code").asText()).isEqualTo("HIV-VL");
      assertThat(mapping.path("specimen_type_hint").asText()).isEqualTo("Plasma");
    });

    assertThat(
      catalog.require("genexpert-astm", 6).profile().path("catalog").path("revisionFingerprint").asText()
    ).isEqualTo("sha256:72e4d3ca7ebe5715c750a19579e038178709722294ee78686bf0f751f9253001");
    assertThat(catalog.require("genexpert-astm", 7).profile().path("default_test_mappings")).anySatisfy(mapping -> {
      assertThat(mapping.path("test_code").asText()).isEqualTo("COVID19");
      assertThat(mapping.path("specimen_type_hint").asText()).isEqualTo("Respiratory Swab");
      assertThat(mapping.path("result_value_hints").path("POSITIVE").asText()).isEqualTo("SARS-CoV-2 RNA DETECTED");
      assertThat(mapping.path("result_value_hints").path("NEGATIVE").asText()).isEqualTo("SARS-COV-2 RNA NOT DETECTED");
      assertThat(mapping.path("result_value_hints").has("ERROR")).isFalse();
    });

    assertThat(
      catalog.require("fluorocycler-xt", 3).profile().path("catalog").path("revisionFingerprint").asText()
    ).isEqualTo("sha256:566234eb28f34c491c5bc9cdccdace50a303d622c67c0d06f5b1b8076bee8525");
    ObjectNode fluoro = catalog.requireLatest("fluorocycler-xt").profile();
    assertThat(fluoro.path("catalog").path("revision").asInt()).isEqualTo(4);
    assertThat(fluoro.path("default_test_mappings"))
      .singleElement()
      .satisfies(mapping -> {
        assertThat(mapping.path("test_code").asText()).isEqualTo("VIH-1");
        assertThat(mapping.path("specimen_type_hint").asText()).isEqualTo("Plasma");
        assertThat(mapping.path("unit").asText()).isEqualTo("copies/mL");
      });

    REVISION_ONE_FINGERPRINTS.forEach((profileId, fingerprint) -> {
      ObjectNode revisionOne = catalog.require(profileId, 1).profile();
      assertThat(revisionOne.path("catalog").path("revisionFingerprint").asText()).isEqualTo(fingerprint);
      assertThat(revisionOne.path("configDefaults").has("dataFlow")).isFalse();
      assertThat(
        StreamSupport.stream(revisionOne.path("connectionFields").spliterator(), false).noneMatch(
          field -> "dataFlow".equals(field.path("key").asText())
        )
      ).isTrue();
    });
  }
}
