package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.Hl7ResultRecordSelection;
import org.itech.ahb.profile.Hl7SpecimenPosition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.Test;

/**
 * An HL7 profile that declares result parts is read observation by observation, each part from
 * the field the profile names. The message is the one Mindray prints in its BC-5380 LIS protocol,
 * appendix C.3.3.
 */
class HL7ResultPartsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Map<String, String> PARTS = Map.ofEntries(
    Map.entry("testCode", "OBX.3.1"),
    Map.entry("value", "OBX.5"),
    Map.entry("unit", "OBX.6.1"),
    Map.entry("range", "OBX.7"),
    Map.entry("flag", "OBX.8"),
    Map.entry("status", "OBX.11"),
    Map.entry("note", "NTE.3"),
    Map.entry("completed", "OBR.7"),
    Map.entry("operator", "OBR.32"),
    Map.entry("patientId", "PID.3.1"),
    Map.entry("patientName", "PID.5"),
    Map.entry("specimenDescriptor", "OBR.15")
  );

  @Test
  void everyPartOfAnObservationIsReadFromTheFieldTheProfileNames() throws IOException {
    ParsedResults parsed = parse(manualMessage(), selection("ALL"));

    assertThat(parsed.accessionNumber()).isEqualTo("20071207011");
    assertThat(parsed.patient()).isEqualTo(new InstrumentPatient("7393670", "Joan", "JIang"));
    AnalyzerResult wbc = byCode(parsed).get("6690-2");
    assertThat(wbc.isNumeric()).isTrue();
    assertThat(wbc.value()).isEqualTo("9.81");
    assertThat(wbc.units()).isEqualTo("10*9/L");
    assertThat(wbc.timestamp()).startsWith("2008-05-08T15:06:16");
    assertThat(wbc.parts().number()).isEqualTo("9.81");
    assertThat(wbc.parts().call()).isNull();
    assertThat(wbc.parts().range()).isEqualTo("4.00-10.00");
    assertThat(wbc.parts().flags()).containsExactly("N");
    assertThat(wbc.parts().status()).isEqualTo("F");
    assertThat(wbc.parts().operator()).isEqualTo("Mindray");
    AnalyzerResult hgb = byCode(parsed).get("718-7");
    assertThat(hgb.value()).isEqualTo("65");
    assertThat(hgb.units()).isEqualTo("g/L");
    assertThat(hgb.parts().flags()).containsExactly("L");
  }

  @Test
  void aValueTheObservationDoesNotTypeAsANumberIsItsCall() throws IOException {
    AnalyzerResult anemia = byCode(parse(manualMessage(), selection("ALL"))).get("12014");

    assertThat(anemia.isNumeric()).isFalse();
    assertThat(anemia.value()).isEqualTo("T");
    assertThat(anemia.parts().call()).isEqualTo("T");
    assertThat(anemia.parts().number()).isNull();
  }

  @Test
  void anObservationWithNoValueIsNotAResult() throws IOException {
    assertThat(byCode(parse(manualMessage(), selection("ALL")))).doesNotContainKey("704-7");
  }

  @Test
  void theRecordSelectionLeavesOutTheCodesTheProfileSaysAreNotResults() throws IOException {
    Map<String, AnalyzerResult> results = byCode(
      parse(manualMessage(), selection("EXCLUDE", "08001", "08002", "08003", "01002", "30525-0", "15008"))
    );

    assertThat(results).doesNotContainKeys("08001", "08002", "08003", "01002", "30525-0", "15008");
    assertThat(results).containsKeys("6690-2", "10002", "747-6", "15001");
  }

  @Test
  void repeatedFlagsAreEachAFlagAndANoteFollowsItsObservation() {
    List<String> message = List.of(
      "MSH|^~\\&|BC-5380|Mindray|||20080617143943||ORU^R01|1|P|2.3.1",
      "OBR|1||S-1|00001^Automated Count^99MRC",
      "OBX|1|NM|6690-2^WBC^LN||12.5|10*9/L|4.00-10.00|H~A|||F",
      "NTE|1||Repeat with a fresh sample"
    );

    AnalyzerResult wbc = HL7ResultParser.parse(
      message,
      ControlResultRecognition.none(),
      Hl7SpecimenPosition.PRECEDING,
      selection("ALL"),
      reading()
    )
      .results()
      .get(0);

    assertThat(wbc.parts().flags()).containsExactly("H", "A");
    assertThat(wbc.parts().notes()).containsExactly("Repeat with a fresh sample");
  }

  @Test
  void aRaisedAlarmTheProfileNamesTravelsWithTheSampleAndIsNeverAResult() throws IOException {
    ParsedResults parsed = HL7ResultParser.parse(
      manualMessage(),
      ControlResultRecognition.none(),
      Hl7SpecimenPosition.PRECEDING,
      selection("ALL"),
      readingWithAlarms("12014", "15180-3", "12018")
    );

    assertThat(byCode(parsed)).doesNotContainKeys("12014", "15180-3").containsKeys("6690-2", "718-7");
    assertThat(parsed.sampleFlags()).containsExactly(
      new SampleFlag("12014", "Anemia", "T"),
      new SampleFlag("15180-3", "Hypochromia", "T")
    );
  }

  @Test
  void anAlarmNotRaisedSaysNothing() {
    List<String> message = List.of(
      "MSH|^~\\&|BC-5380|Mindray|||20080617143943||ORU^R01|1|P|2.3.1",
      "OBR|1||S-1|00001^Automated Count^99MRC",
      "OBX|1|NM|6690-2^WBC^LN||9.81|10*9/L|4.00-10.00|N|||F",
      "OBX|2|IS|12014^Anemia^99MRC||F||||||F"
    );

    ParsedResults parsed = HL7ResultParser.parse(
      message,
      ControlResultRecognition.none(),
      Hl7SpecimenPosition.PRECEDING,
      selection("ALL"),
      readingWithAlarms("12014")
    );

    assertThat(byCode(parsed)).containsOnlyKeys("6690-2");
    assertThat(parsed.sampleFlags()).isEmpty();
  }

  private static ParsedResults parse(List<String> message, Hl7ResultRecordSelection selection) {
    return HL7ResultParser.parse(
      message,
      ControlResultRecognition.none(),
      Hl7SpecimenPosition.PRECEDING,
      selection,
      reading()
    );
  }

  private static ResultReading readingWithAlarms(String... codes) {
    ObjectNode profile = JSON.createObjectNode();
    profile.putObject("protocol").put("name", "HL7");
    ObjectNode overrides = profile.putObject("configDefaults").putObject("extractionOverrides");
    PARTS.forEach(overrides.putObject("resultParts")::put);
    ObjectNode flags = overrides.putObject("sampleFlags");
    flags.put("codeField", "OBX.3.1");
    flags.put("nameField", "OBX.3.2");
    flags.put("valueField", "OBX.5");
    flags.put("raisedValue", "T");
    Arrays.stream(codes).forEach(flags.putArray("codes")::add);
    return ResultReading.fromProfile(profile);
  }

  private static Map<String, AnalyzerResult> byCode(ParsedResults parsed) {
    return parsed.results().stream().collect(Collectors.toMap(AnalyzerResult::testCode, Function.identity()));
  }

  private static ResultReading reading() {
    ObjectNode profile = JSON.createObjectNode();
    profile.putObject("protocol").put("name", "HL7");
    ObjectNode parts = profile.putObject("configDefaults").putObject("extractionOverrides").putObject("resultParts");
    PARTS.forEach(parts::put);
    return ResultReading.fromProfile(profile);
  }

  private static Hl7ResultRecordSelection selection(String mode, String... codes) {
    ObjectNode configDefaults = JSON.createObjectNode();
    ObjectNode selection = configDefaults.putObject("extractionOverrides").putObject("resultRecordSelection");
    selection.put("mode", mode);
    if (codes.length > 0) {
      selection.put("targetField", "OBX.3.1");
      Arrays.stream(codes).forEach(selection.putArray("values")::add);
    }
    return Hl7ResultRecordSelection.fromProfile(configDefaults);
  }

  private static List<String> manualMessage() throws IOException {
    try (InputStream in = HL7ResultPartsTest.class.getResourceAsStream("/mindray-examples/bc5380-c.3.3.hl7")) {
      return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
        .filter(line -> !line.isBlank())
        .toList();
    }
  }
}
