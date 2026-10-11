package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.fhir.InstrumentPatient;
import org.itech.ahb.fhir.SampleFlag;
import org.junit.jupiter.api.Test;

/**
 * The shipped Mindray BC-5380 baseline profile against the messages Mindray prints in the BC-5380
 * Operator's Manual, appendix C: the analysis result of section C.3.3 and its X-R QC message.
 */
class MindrayBc5380BaselineProfileTest {

  private static final Path EXAMPLES = Path.of("src", "test", "resources", "mindray-examples");
  /** The manual differential the C.3.3 message sends in its microscope exam (OBR-4 00002). */
  private static final List<String> MICROSCOPE_EXAM_SENT = List.of(
    "747-6",
    "783-1",
    "749-2",
    "740-1",
    "764-1",
    "769-0",
    "714-6",
    "707-0",
    "33831-9",
    "6746-2",
    "737-7",
    "29261-5",
    "33840-0",
    "13599-6",
    "744-3",
    "18309-5",
    "31112-6",
    "11000",
    "11001"
  );
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void theProfileIsValidAndFingerprinted() throws Exception {
    ObjectNode profile = profile();
    assertThat(new AnalyzerProfileValidator(objectMapper).validationIssues(profile)).isEmpty();
    assertThat(profile.path("schemaVersion").asText()).isEqualTo("2.0");
    assertThat(profile.path("catalog").path("revision").asInt()).isEqualTo(1);
    assertThat(profile.path("protocol").path("name").asText()).isEqualTo("HL7");
  }

  @Test
  void everyAnalysisResultTheExampleSendsIsDeclaredWithTheUnitMindraySendsIt() throws Exception {
    Map<String, JsonNode> declared = declaredTests();
    ParsedResults parsed = replay("bc5380-c.3.3.hl7");

    Map<String, AnalyzerResult> declaredResults = new LinkedHashMap<>();
    parsed
      .results()
      .stream()
      .filter(result -> declared.containsKey(result.testCode()))
      .forEach(result -> declaredResults.put(result.testCode(), result));
    assertThat(declaredResults.keySet()).containsExactly(
      "6690-2",
      "789-8",
      "718-7",
      "787-2",
      "785-6",
      "786-4",
      "788-0",
      "21000-5",
      "4544-3",
      "777-3",
      "32623-1",
      "32207-3",
      "10002"
    );
    declaredResults.forEach((code, result) -> {
      assertThat(result.isNumeric()).as(code).isTrue();
      assertThat(result.units()).as(code).isEqualTo(declared.get(code).path("unit").asText(null));
      assertThat(result.parts().range()).as(code).isNotBlank();
      assertThat(result.parts().flags()).as(code).isNotEmpty();
    });
    assertThat(declaredResults.get("718-7").value()).isEqualTo("65");
    assertThat(declaredResults.get("718-7").parts().flags()).containsExactly("L");
    // Every declared test is a row of the example, with or without a value.
    assertThat(obxCodes("bc5380-c.3.3.hl7")).containsAll(declared.keySet());
  }

  @Test
  void theExampleArrivesAsTheDeclaredResultsAndTheAlarmsItRaised() throws Exception {
    ParsedResults parsed = replay("bc5380-c.3.3.hl7");

    // Settings and histograms are not results and the alarms travel with the sample. The microscope
    // exam arrives as results the profile does not declare: each lab maps or excludes them.
    Set<String> codes = new LinkedHashSet<>();
    parsed.results().forEach(result -> codes.add(result.testCode()));
    codes.removeAll(declaredTests().keySet());
    assertThat(codes).containsExactlyInAnyOrderElementsOf(MICROSCOPE_EXAM_SENT);
    assertThat(parsed.sampleFlags()).containsExactly(
      new SampleFlag("12014", "Anemia", "T"),
      new SampleFlag("15180-3", "Hypochromia", "T")
    );
  }

  @Test
  void everyAlarmTable10ListsIsASampleFlagAndTheMicroscopeExamReachesTheLab() throws Exception {
    JsonNode overrides = profile().path("configDefaults").path("extractionOverrides");
    Set<String> flags = new LinkedHashSet<>();
    overrides.path("sampleFlags").path("codes").forEach(code -> flags.add(code.asText()));
    Set<String> notResults = new LinkedHashSet<>();
    overrides.path("resultRecordSelection").path("values").forEach(value -> notResults.add(value.asText()));

    Set<String> alarms = new LinkedHashSet<>();
    for (int code = 12000; code <= 12018; code++) {
      alarms.add(String.valueOf(code));
    }
    alarms.addAll(
      List.of(
        "17790-7",
        "34165-1",
        "15192-8",
        "34525-6",
        "15150-6",
        "15198-5",
        "15199-3",
        "10379-6",
        "15180-3",
        "7796-6"
      )
    );
    assertThat(flags).containsExactlyInAnyOrderElementsOf(alarms);
    assertThat(notResults).doesNotContainAnyElementsOf(
      List.of(
        "882-1",
        "11156-7",
        "6742-1",
        "11125-2",
        "747-6",
        "783-1",
        "749-2",
        "740-1",
        "764-1",
        "769-0",
        "714-6",
        "707-0",
        "33831-9",
        "6746-2",
        "737-7",
        "29261-5",
        "33840-0",
        "13599-6",
        "744-3",
        "18309-5",
        "31112-6",
        "11000",
        "11001"
      )
    );
  }

  @Test
  void thePatientAndSpecimenTheInstrumentReportsTravelWithTheResults() throws Exception {
    ParsedResults parsed = replay("bc5380-c.3.3.hl7");

    assertThat(parsed.accessionNumber()).isEqualTo("20071207011");
    assertThat(parsed.patient()).isEqualTo(new InstrumentPatient("7393670", "Joan", "JIang"));
    assertThat(parsed.specimenDescriptor()).isEqualTo("BLDV");
    assertThat(parsed.results().get(0).parts().operator()).isEqualTo("Mindray");
  }

  @Test
  void aQcMessageIsAControlRun() throws Exception {
    ParsedResults parsed = replay("bc5380-c.3.3-xr-qc.hl7");

    assertThat(parsed.results()).isNotEmpty().allSatisfy(result -> assertThat(result.isControl()).isTrue());
  }

  private ParsedResults replay(String example) throws Exception {
    ObjectNode profile = profile();
    JsonNode configDefaults = profile.path("configDefaults");
    return HL7ResultParser.parse(
      lines(example),
      ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")),
      Hl7SpecimenPosition.fromProfile(configDefaults),
      Hl7ResultRecordSelection.fromProfile(configDefaults),
      ResultReading.fromProfile(profile)
    );
  }

  private Map<String, JsonNode> declaredTests() throws Exception {
    Map<String, JsonNode> declared = new LinkedHashMap<>();
    profile().path("default_test_mappings").forEach(test -> declared.put(test.path("test_code").asText(), test));
    return declared;
  }

  private static Set<String> obxCodes(String example) throws Exception {
    Set<String> codes = new LinkedHashSet<>();
    for (String line : lines(example)) {
      if (line.startsWith("OBX|")) {
        codes.add(line.split("\\|", -1)[3].split("\\^", -1)[0]);
      }
    }
    return codes;
  }

  private static List<String> lines(String example) throws Exception {
    return Arrays.stream(Files.readString(EXAMPLES.resolve(example), StandardCharsets.UTF_8).split("\n"))
      .filter(line -> !line.isBlank())
      .toList();
  }

  private ObjectNode profile() throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream("analyzer-profiles/mindray-bc5380.json")) {
      assertThat(input).isNotNull();
      return (ObjectNode) objectMapper.readTree(input);
    }
  }
}
