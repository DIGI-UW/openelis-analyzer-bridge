package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The latest shipped revision of each file profile classifies rows shaped like the
 * documented inputs: QuantStudio plates per the QuantStudio integration spec §7, and
 * the FluoroCycler XT site file's Type column.
 */
@DisplayName("Shipped file profiles recognise controls as their inputs name them")
class ShippedFileProfileControlRecognitionTest {

  private static ControlResultRecognition latestRecognition(String profileId) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode latest = null;
    for (Resource resource : new PathMatchingResourcePatternResolver().getResources(
        "classpath*:analyzer-profiles/*.json")) {
      JsonNode profile = mapper.readTree(resource.getInputStream());
      if (!profileId.equals(profile.path("profileMeta").path("id").asText())) {
        continue;
      }
      if (latest == null
          || profile.path("catalog").path("revision").asInt()
              > latest.path("catalog").path("revision").asInt()) {
        latest = profile;
      }
    }
    assertThat(latest).as(profileId).isNotNull();
    return ControlResultRecognition.fromProfile(latest.path("controlResultRecognition"));
  }

  @ParameterizedTest(name = "QuantStudio {0} / {1} is a control")
  @CsvSource({
    "PC, UNKNOWN",
    "pc, UNKNOWN",
    "pos, UNKNOWN",
    "positive, UNKNOWN",
    "controle positif, UNKNOWN",
    "NC, NTC",
    "NTC, NTC",
    "STD-1, STANDARD"
  })
  void quantStudioControls(String sampleName, String task) throws Exception {
    assertThat(FileResultParser.isControlRow(sampleName, task, latestRecognition("thermo-quantstudio")))
        .isTrue();
  }

  @ParameterizedTest(name = "QuantStudio {0} / {1} is a patient result")
  @CsvSource({
    "LM12345, UNKNOWN",
    "LL0098, UNKNOWN",
    "PCR-0042, UNKNOWN",
    "CPOS, UNKNOWN",
    "CNEG, UNKNOWN"
  })
  void quantStudioPatients(String sampleName, String task) throws Exception {
    assertThat(FileResultParser.isControlRow(sampleName, task, latestRecognition("thermo-quantstudio")))
        .isFalse();
  }

  @ParameterizedTest(name = "FluoroCycler Type {1} is a control")
  @CsvSource({"CTRL-A, Positive", "CTRL-B, Negative", "CAL-1, Standard"})
  void fluoroCyclerControls(String sampleId, String type) throws Exception {
    assertThat(FileResultParser.isControlRow(sampleId, type, latestRecognition("hain-fluorocycler-xt")))
        .isTrue();
  }

  @Test
  @DisplayName("FluoroCycler: a patient row, and an unsourced C+ prefix, are patient results")
  void fluoroCyclerPatients() throws Exception {
    ControlResultRecognition recognition = latestRecognition("hain-fluorocycler-xt");
    assertThat(FileResultParser.isControlRow("DEV01263000000000001", "Unknown", recognition)).isFalse();
    assertThat(FileResultParser.isControlRow("C+1", "Unknown", recognition)).isFalse();
    assertThat(FileResultParser.isControlRow("C-1", "Unknown", recognition)).isFalse();
  }
}
