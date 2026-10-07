package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HL7RecognitionFieldTest {

  static Stream<Arguments> selectedFields() {
    return Stream.of(
      Arguments.of("MSH.1", "|"),
      Arguments.of("MSH.2", "^~\\&"),
      Arguments.of("MSH.3", "SENDER^CONTROL"),
      Arguments.of("MSH.3.2", "CONTROL"),
      Arguments.of("MSH.9.2", "R01"),
      Arguments.of("PID.3.4.2", "CONTROL"),
      Arguments.of("OBR.4.2", "CONTROL"),
      Arguments.of("ZQC.1.1", "CONTROL")
    );
  }

  @ParameterizedTest
  @MethodSource("selectedFields")
  void exposesProfileSelectedFieldsAndComponents(String target, String operand) {
    var parsed = HL7ResultParser.parse(
      List.of(
        "MSH|^~\\&|SENDER^CONTROL|LAB|OE|LAB|20260909||ORU^R01|1|P|2.5.1",
        "PID|1||PATIENT^^^AUTHORITY&CONTROL",
        "OBR|1||ACC001|PANEL^CONTROL",
        "ZQC|CONTROL",
        "OBX|1|NM|TEST||7.5|unit"
      ),
      TestControlRecognitions.rule("FIELD_EQUALS", target, operand)
    );

    assertNotNull(parsed);
    assertTrue(parsed.results().get(0).isControl(), target);
  }

  @Test
  void observationFieldDoesNotClassifyOtherObservations() {
    var parsed = HL7ResultParser.parse(
      List.of(
        "OBR|1||ACC001|PANEL",
        "OBX|1|NM|FIRST^PATIENT||1|unit",
        "OBX|2|NM|SECOND^CONTROL||2|unit",
        "OBX|3|NM|THIRD||3|unit"
      ),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBX.3.2", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals(List.of(false, true, false), parsed.results().stream().map(result -> result.isControl()).toList());
  }

  @Test
  void laterOrderDoesNotChangeEarlierObservationRecognition() {
    var parsed = HL7ResultParser.parse(
      List.of("OBR|1||ACC001|CONTROL", "OBX|1|NM|FIRST||1|unit", "OBR|2||ACC001|PATIENT", "OBX|1|NM|SECOND||2|unit"),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBR.4", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals(List.of(true, false), parsed.results().stream().map(result -> result.isControl()).toList());
  }

  @Test
  void actualFieldCanRecognizeControlWithoutAnAccession() {
    var parsed = HL7ResultParser.parse(
      List.of("OBX|1|NM|TEST^CONTROL||1|unit"),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBX.3.2", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals("HL7-UNKNOWN", parsed.accessionNumber());
    assertTrue(parsed.results().get(0).isControl());
  }

  @Test
  void usesMessageDeclaredDelimitersForRecognitionAndResultParsing() {
    var parsed = HL7ResultParser.parse(
      List.of(
        "MSH#$!?@#SENDER#LAB#OE#LAB#20260909##ORU$R01#1#P#2.5.1",
        "PID#1##PATIENT$$$AUTHORITY@CONTROL",
        "OBR#1##ACCESSION#PANEL",
        "OBX#1#NM#TEST$Display##7.5#unit$Display"
      ),
      TestControlRecognitions.rule("FIELD_EQUALS", "PID.3.4.2", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals("ACCESSION", parsed.accessionNumber());
    assertTrue(parsed.results().get(0).isControl());
    assertEquals("TEST", parsed.results().get(0).testCode());
    assertEquals("7.5", parsed.results().get(0).value());
    assertEquals("unit", parsed.results().get(0).units());
  }

  @Test
  void aShorterRepeatedSegmentDoesNotRetainOldTrailingFields() {
    var parsed = HL7ResultParser.parse(
      List.of("OBR|1||ACC001|CONTROL", "OBX|1|NM|FIRST||1|unit", "OBR|2||ACC001", "OBX|1|NM|SECOND||2|unit"),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBR.4", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals(List.of(true, false), parsed.results().stream().map(result -> result.isControl()).toList());
  }

  @Test
  void componentReferenceDoesNotFlattenRepetitionsIntoControlEvidence() {
    var parsed = HL7ResultParser.parse(
      List.of("OBX|1|NM|TEST^PATIENT~TEST^CONTROL||1|unit"),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBX.3.2", "CONTROL")
    );

    assertNotNull(parsed);
    assertEquals(false, parsed.results().get(0).isControl());
  }
}
