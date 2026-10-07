package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.itech.ahb.fhir.ASTMResultParser;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.junit.jupiter.api.Test;

/**
 * The shipped GeneXpert baseline profile against every example message Cepheid documents for the
 * assays it declares: each record an instrument sends is a test, component and value the profile
 * declares, and each test, component and value the profile declares is one Cepheid shows.
 */
class GeneXpertBaselineProfileTest {

  private static final Path EXAMPLES = Path.of("src", "test", "resources", "cepheid-examples");
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void theProfileIsValidAndFingerprinted() throws Exception {
    ObjectNode profile = profile();
    assertThat(new AnalyzerProfileValidator(objectMapper).validationIssues(profile)).isEmpty();
    assertThat(profile.path("schemaVersion").asText()).isEqualTo("2.0");
    assertThat(profile.path("catalog").path("revision").asInt()).isEqualTo(8);
  }

  @Test
  void everyRecordCepheidShowsIsDeclaredAndEveryDeclaredRowIsShownOnce() throws Exception {
    ObjectNode profile = profile();
    // Cepheid's single-result SARS-CoV-2 plus examples are written with the host test code COVPLUS;
    // a lab that uses it says so, as any connection can.
    ObjectNode values = objectMapper.createObjectNode();
    values.putObject("codeOverrides").put("SARSCOV2_3", "COVPLUS");
    ResultReading reading = ResultReading.fromProfile(profile, values);

    Set<String> declaredRecords = new LinkedHashSet<>();
    Set<String> declaredValues = new LinkedHashSet<>();
    for (JsonNode test : profile.path("default_test_mappings")) {
      String code = test.path("test_code").asText();
      if (!test.has("components") && "text".equals(test.path("result_type").asText())) {
        continue; // MTB and RIF are declared without vendor-documented values or records
      }
      declaredRecords.add(code + "|");
      test.path("values").forEach(value -> declaredValues.add(code + "||" + value.asText()));
      for (JsonNode component : test.path("components")) {
        if (!component.has("sub_identity")) {
          continue;
        }
        String sub = component.path("sub_identity").asText();
        declaredRecords.add(code + "|" + sub);
        component.path("values").forEach(value -> declaredValues.add(code + "|" + sub + "|" + value.asText()));
      }
    }

    Set<String> seenRecords = new LinkedHashSet<>();
    Set<String> seenValues = new LinkedHashSet<>();
    List<String> undeclared = new ArrayList<>();
    for (Path example : examples()) {
      ParsedResults parsed = ASTMResultParser.parseRaw(
        Files.readString(example, StandardCharsets.UTF_8),
        ControlResultRecognition.none(),
        AstmResultRecordSelection.all(),
        reading
      );
      assertThat(parsed).as(example.getFileName().toString()).isNotNull();
      for (AnalyzerResult result : parsed.results()) {
        String code = reading.profileCode(result.testCode());
        String sub = result.parts().subIdentity();
        String record = code + "|" + sub;
        if (!declaredRecords.contains(record)) {
          undeclared.add(example.getFileName() + " " + record);
          continue;
        }
        seenRecords.add(record);
        String call = result.parts().call();
        if (call != null) {
          String value = code + "|" + sub + "|" + call;
          if (declaredValues.contains(value)) {
            seenValues.add(value);
            if (code.equals("SARSCOV2")) {
              // 302-7279 prints its single-result error and invalid examples under the two-assay
              // panel's code (6.3.1.3 and 6.3.1.4), so the same values are shown there only.
              seenValues.add(value.replaceFirst("^SARSCOV2\\|", "SARSCOV2_3|"));
            }
          } else if (!result.parts().runFailed()) {
            undeclared.add(example.getFileName() + " value " + value);
          }
        }
      }
    }

    assertThat(undeclared).as("records and values the profile does not declare").isEmpty();
    Set<String> unseenRecords = new LinkedHashSet<>(declaredRecords);
    unseenRecords.removeAll(seenRecords);
    assertThat(unseenRecords).as("declared records no Cepheid example shows").isEmpty();
    Set<String> unseenValues = new LinkedHashSet<>(declaredValues);
    unseenValues.removeAll(seenValues);
    assertThat(unseenValues).as("declared values no Cepheid example shows").isEmpty();
  }

  private static List<Path> examples() throws IOException {
    try (Stream<Path> files = Files.list(EXAMPLES)) {
      return files.filter(path -> path.toString().endsWith(".astm")).sorted().toList();
    }
  }

  private ObjectNode profile() throws Exception {
    try (
      InputStream input = getClass().getClassLoader().getResourceAsStream("analyzer-profiles/genexpert-astm-v8.json")
    ) {
      assertThat(input).isNotNull();
      return (ObjectNode) objectMapper.readTree(input);
    }
  }
}
